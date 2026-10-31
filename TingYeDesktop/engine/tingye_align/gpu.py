"""Batched SenseVoice inference; CTC alignment stays on CPU to avoid hundreds
of tiny CUDA launches per sentence. Adapted from FunASR's MIT implementation.
No changes to the installed model or weights are required.
"""
from __future__ import annotations
from itertools import groupby
import numpy as np


def ctc_path(emissions: np.ndarray, targets: list[int], blank: int = 0) -> np.ndarray:
    """CTC Viterbi with the same transition order and tie rules as FunASR.
    emissions contains blank followed by one column per target (duplicates OK).
    """
    length=len(targets)
    if not length:return np.zeros(len(emissions),dtype=np.int64)
    labels=np.full(length*2+1,blank,dtype=np.int64);labels[1::2]=targets
    columns=np.zeros(len(labels),dtype=np.int64);columns[1::2]=np.arange(1,length+1)
    different=np.zeros(len(labels),dtype=bool);different[2:]=labels[2:]!=labels[:-2]
    scores=np.full(len(labels)+2,-np.inf,dtype=np.float32)
    scores[2:4]=emissions[0,[0,1]]
    back=np.zeros((len(emissions),len(labels)),dtype=np.uint8)
    for t in range(1,len(emissions)):
        previous=np.stack((scores[2:],scores[1:-1],np.where(different,scores[:-2],-np.inf)))
        transition=previous.argmax(axis=0)
        scores[2:]=emissions[t,columns]+np.take_along_axis(previous,transition[None,:],axis=0)[0]
        back[t]=transition
    state=2*length-1+int(scores[-1]>scores[-2])
    result=np.empty(len(emissions),dtype=np.int64)
    result[-1]=labels[state]
    for t in range(len(emissions)-1,0,-1):
        state-=int(back[t,state]);result[t-1]=labels[max(0,state)]
    return result


def _logits(auto_model, samples:list[np.ndarray], mixed_precision:bool=False):
    import torch
    from funasr.utils.load_utils import extract_fbank
    from contextlib import nullcontext
    model=auto_model.model
    frontend=auto_model.kwargs['frontend'];tokenizer=auto_model.kwargs['tokenizer']
    speech,lengths=extract_fbank([torch.from_numpy(sample) for sample in samples],data_type='sound',frontend=frontend)
    speech=speech.to(device='cuda',dtype=next(model.parameters()).dtype);lengths=lengths.to('cuda')
    amp=torch.autocast('cuda',dtype=torch.bfloat16) if mixed_precision else nullcontext()
    with torch.inference_mode(),amp:
        batch=speech.shape[0]
        # Language, event, emotion and text normalization prefix, in model order.
        prefix=torch.tensor([[model.lid_dict['zh'],1,2,model.textnorm_dict['woitn']]],device='cuda')
        speech=torch.cat((model.embed(prefix).expand(batch,-1,-1),speech),dim=1)
        encoded,encoded_lengths=model.encoder(speech,lengths+4)
        if isinstance(encoded,tuple):encoded=encoded[0]
        logits=model.ctc.log_softmax(encoded)
    return model,tokenizer,logits,encoded_lengths


def recognize(auto_model, samples:list[np.ndarray], mixed_precision:bool=False) -> list[dict]:
    import torch
    model,tokenizer,logits,encoded_lengths=_logits(auto_model,samples,mixed_precision)
    predictions=logits.argmax(-1).cpu().numpy()
    valid_lengths=encoded_lengths.cpu().tolist()
    output=[]
    for i,size in enumerate(valid_lengths):
        ids=[int(label) for label,_ in groupby(predictions[i,:size]) if label!=model.blank_id]
        text=tokenizer.decode(ids)
        tokens=tokenizer.text2tokens(text)[4:]
        targets=[]
        for group in tokenizer.tokens2ids(tokens):targets.extend(group or [124])
        if not targets:
            output.append(dict(text=text));continue
        # Transfer only the needed vocabulary columns, once per clip.
        selected=torch.tensor([model.blank_id,*targets],device=logits.device)
        emission=logits[i,4:size].index_select(-1,selected).float().cpu().numpy()
        emission[predictions[i,4:size]==model.blank_id,0]=0
        aligned=ctc_path(emission,targets,model.blank_id)
        timestamp=[];start=0;token=0
        for label,frames in groupby(aligned):
            end=start+sum(1 for _ in frames)
            if label!=model.blank_id:
                if token>=len(tokens):raise ValueError('CTC 词元数量不一致')
                timestamp.append([tokens[token],max((start*60-30)/1000,0),min((end*60-30)/1000,((size-4)*60-30)/1000)])
                token+=1
            start=end
        if token!=len(tokens):raise ValueError('CTC 句子时间对齐不完整')
        spans,words=model.post(timestamp)
        output.append(dict(text=text,timestamp=spans,words=words))
    return output


def force_align(auto_model,samples:list[np.ndarray],texts:list[str],mixed_precision:bool=False)->list[dict]:
    """Align independently ASR-confirmed text against fresh acoustic emissions.
    Callers must not use this to force an entire novel gap over advertisements.
    """
    import torch
    model,tokenizer,logits,lengths=_logits(auto_model,samples,mixed_precision)
    output=[]
    for i,(text,size) in enumerate(zip(texts,lengths.cpu().tolist())):
        tokens=tokenizer.text2tokens(text)
        targets=tokenizer.encode(text)
        if not targets or len(tokens)!=len(targets) or len(targets)+sum(a==b for a,b in zip(targets,targets[1:]))>size-4:
            output.append(dict(text=text,alignment_score=0.,timestamp=[],words=[]));continue
        selected=torch.tensor([model.blank_id,*targets],device=logits.device)
        emission=logits[i,4:size].index_select(-1,selected).float().cpu().numpy()
        path=ctc_path(emission,targets,model.blank_id)
        timestamps=[];scores=[];at=0;token=0
        for label,frames in groupby(path):
            end=at+sum(1 for _ in frames)
            if label!=model.blank_id:
                if token>=len(tokens):break
                scores.append(float(np.exp(emission[at:end,token+1].max())))
                timestamps.append([tokens[token],max((at*60-30)/1000,0),max((end*60-30)/1000,0)])
                token+=1
            at=end
        if token!=len(tokens):
            output.append(dict(text=text,alignment_score=0.,timestamp=[],words=[]));continue
        spans,words=model.post(timestamps)
        output.append(dict(text=text,timestamp=spans,words=words,
                           alignment_score=float(np.mean(scores)),token_scores=scores))
    return output


class GpuRecognizer:
    def __init__(self,model,memory_gib=6):
        import torch
        self.model=model
        free,total=torch.cuda.mem_get_info()
        requested=max(1,min(32,float(memory_gib)))*2**30
        self.budget=min(requested,total*.9,max(.5*2**30,free+torch.cuda.memory_reserved()-.75*2**30))
        torch.cuda.set_per_process_memory_fraction(self.budget/total)
        estimate=max(1,int((self.budget/2**30-(.65 if memory_gib<2 else 1.25))/.105))
        self.batch_size=max(size for size in [1,2,4,8,16,24,32,48,64] if size<=estimate)
        torch.cuda.reset_peak_memory_stats()

    def __call__(self,samples):
        import torch,gc
        count=min(len(samples),self.batch_size)
        try:
            result=recognize(self.model,samples[:count],mixed_precision=next(self.model.model.parameters()).dtype==torch.bfloat16)
        except torch.OutOfMemoryError:
            result=None
        if result is None:
            gc.collect();torch.cuda.empty_cache()
            if count<=1:raise RuntimeError('可用显存不足，请关闭其他占用 GPU 的程序后重试')
            self.batch_size=max(1,count//2)
            return self(samples)
        return result+self(samples[count:]) if count<len(samples) else result

    def verify(self,samples,texts):
        import torch,gc
        count=min(len(samples),self.batch_size)
        try:
            result=force_align(self.model,samples[:count],texts[:count],next(self.model.model.parameters()).dtype==torch.bfloat16)
        except torch.OutOfMemoryError:
            result=None
        if result is None:
            gc.collect();torch.cuda.empty_cache()
            if count<=1:raise RuntimeError('可用显存不足，无法完成短句核验')
            self.batch_size=max(1,count//2);return self.verify(samples,texts)
        return result+self.verify(samples[count:],texts[count:]) if count<len(samples) else result
