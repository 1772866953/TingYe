import {useEffect,useRef,useState,type KeyboardEvent} from 'react'
import {motion,useReducedMotion} from 'motion/react'
import {BookOpen,Check,ChevronDown,Download,FolderOpen,History,LoaderCircle,RefreshCw,Square,Trash2,X} from 'lucide-react'
import {GlassButton} from './glass'
import type {HistoryRecord} from './api'
const date=(value:number)=>new Date(value).toLocaleString('zh-CN',{year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false})
const size=(bytes:number)=>bytes<1048576?`${(bytes/1024).toFixed(0)} KB`:`${(bytes/1048576).toFixed(1)} MB`
const errorText=(e:unknown)=>String(e instanceof Error?e.message:e).replace(/^Error invoking remote method '[^']+':\s*(?:Error:\s*)?/,'')

export default function HistoryDialog({running,onClose}:{running:boolean;onClose:()=>void}){
  const reduced=useReducedMotion(),dialog=useRef<HTMLElement>(null),request=useRef(0)
  const [records,setRecords]=useState<HistoryRecord[]>([]),[loading,setLoading]=useState(true)
  const [managing,setManaging]=useState(false),[chosen,setChosen]=useState<Set<string>>(new Set()),[expanded,setExpanded]=useState('')
  const [pending,setPending]=useState<string[]|null>(null),[busy,setBusy]=useState(''),[error,setError]=useState(''),[message,setMessage]=useState('')
  async function refresh(){
    const token=++request.current;setLoading(true);setError('')
    try{const result=await window.tingye.history();if(token!==request.current)return;setRecords(result);setChosen(old=>new Set([...old].filter(id=>result.some(row=>row.id===id))))}
    catch(e){if(token===request.current)setError(errorText(e))}
    finally{if(token===request.current)setLoading(false)}
  }
  useEffect(()=>{
    const previous=document.activeElement as HTMLElement|null
    dialog.current?.focus();void refresh()
    return()=>{request.current++;previous?.focus()}
  },[])
  function keys(event:KeyboardEvent){
    if(event.key==='Escape'){event.stopPropagation();if(busy)return;if(pending)setPending(null);else onClose()}
    if(event.key==='Tab'){
      const items=dialog.current?.querySelectorAll<HTMLElement>('button:not(:disabled),[tabindex="0"]')
      if(!items?.length)return
      const first=items[0],last=items[items.length-1],active=document.activeElement
      if(event.shiftKey&&(active===first||active===dialog.current)){event.preventDefault();last.focus()}
      else if(!event.shiftKey&&(active===last||active===dialog.current)){event.preventDefault();first.focus()}
    }
  }
  function toggle(id:string){setChosen(old=>{const next=new Set(old);if(next.has(id))next.delete(id);else next.add(id);return next})}
  async function save(record:HistoryRecord){
    setBusy(record.id);setError('');setMessage('')
    try{if(await window.tingye.exportHistory(record.id))setMessage(`已导出《${record.title}》的对应文件`)}catch(e){setError(errorText(e))}finally{setBusy('')}
  }
  async function remove(){
    if(!pending)return
    setBusy('delete');setError('');setMessage('')
    try{
      const result=await window.tingye.removeHistory(pending)
      setPending(null);await refresh()
      if(result.deleted.length)setMessage(`已删除 ${result.deleted.length} 条历史记录`)
      if(result.failed.length)setError(`${result.failed.length} 条记录未能删除：${result.failed[0].message}`)
    }catch(e){setError(errorText(e))}finally{setBusy('')}
  }
  const locked=!!busy||!!pending,all=records.length>0&&chosen.size===records.length
  return <motion.div className="modal-overlay history-overlay" initial={{opacity:0}} animate={{opacity:1}} exit={{opacity:0}} transition={{duration:reduced?0:.18}} onClick={()=>{if(!busy)onClose()}}>
    <motion.section ref={dialog} tabIndex={-1} className="settings-dialog history-dialog" role="dialog" aria-modal="true" aria-labelledby="history-title" onKeyDown={keys} onClick={e=>e.stopPropagation()}
      initial={{opacity:0,scale:.96,y:16}} animate={{opacity:1,scale:1,y:0}} exit={{opacity:0,scale:.97,y:10}} transition={reduced?{duration:0}:{type:'spring',stiffness:390,damping:34,mass:.8}}>
      <div className="settings-header history-header"><div><h2 id="history-title"><History size={20}/>历史记录</h2><p>识别后保存在本机的对应结果</p></div><GlassButton aria-label="关闭历史记录" className="icon-button" radius={999} disabled={!!busy} onClick={onClose}><X size={17}/></GlassButton></div>
      <div className="history-toolbar"><span>{loading?'正在读取缓存…':`${records.length} 条记录`}{managing&&` · 已选 ${chosen.size} 条`}</span><div>
        {managing&&<GlassButton className="text-action" disabled={locked||loading||!records.length} onClick={()=>setChosen(all?new Set():new Set(records.map(r=>r.id)))}>{all?'取消全选':'全选'}</GlassButton>}
        <GlassButton className="text-action" disabled={locked||loading||!records.length} onClick={()=>{setManaging(!managing);setChosen(new Set())}}>{managing?'完成管理':'批量管理'}</GlassButton>
        <GlassButton className="icon-button history-refresh" aria-label="刷新历史记录" disabled={locked||loading} onClick={()=>void refresh()}><RefreshCw size={15} className={loading?'spin':''}/></GlassButton>
      </div></div>
      <div className="settings-scroll history-scroll" tabIndex={0} role="region" aria-label="历史记录列表" aria-busy={loading}>
        {!loading&&!records.length&&!error&&<div className="history-empty"><History size={30}/><h3>暂无历史记录</h3><p>完成识别或暂停保存后，对应结果会显示在这里。</p></div>}
        {records.map(record=><article className={`history-record ${chosen.has(record.id)?'is-selected':''}`} data-history-id={record.id} key={record.id}>
          <div className="history-record-main">
            {managing&&<GlassButton className="history-checkbox icon-button" role="checkbox" aria-checked={chosen.has(record.id)} aria-label={`选择《${record.title}》`} disabled={locked} onClick={()=>toggle(record.id)}>{chosen.has(record.id)?<Check size={17}/>:<Square size={16}/>}</GlassButton>}
            <span className="history-book"><BookOpen size={19}/></span>
            <div className="history-record-info"><h3>{record.title}</h3><p>{date(record.updatedAt)} · {size(record.size)}</p><div className="history-stats"><span className={`history-status ${record.error?'invalid':record.complete?'complete':''}`}>{record.error?'缓存异常':record.complete?'已完成':'部分结果'}</span>{!record.error&&<><span>{record.matched.toLocaleString()} 句对应</span><span>{record.audioCount} 个音频</span><span>正文覆盖 {(record.coverage*100).toFixed(1)}%</span></>}</div></div>
          </div>
          {record.error&&<p className="history-record-error">{record.error}</p>}
          <div className="history-record-actions"><GlassButton className="text-action" aria-expanded={expanded===record.id} disabled={locked} onClick={()=>setExpanded(expanded===record.id?'':record.id)}>详情<ChevronDown size={13} style={{transform:expanded===record.id?'rotate(180deg)':''}}/></GlassButton><div>
            <GlassButton className="secondary history-export" disabled={locked||!!record.error} onClick={()=>void save(record)}>{busy===record.id?<LoaderCircle size={14} className="spin"/>:<Download size={14}/>}重新导出</GlassButton>
            <GlassButton className="icon-button danger" aria-label={`删除《${record.title}》`} title={running?'请先暂停当前处理':'删除历史记录'} disabled={locked||running} onClick={()=>{setError('');setMessage('');setPending([record.id])}}><Trash2 size={15}/></GlassButton>
          </div></div>
          {expanded===record.id&&<div className="history-details"><div><b>缓存文件</b><p>{record.cachePath}</p><GlassButton className="text-action" disabled={locked} onClick={()=>window.tingye.revealHistory(record.id).catch(e=>setError(errorText(e)))}><FolderOpen size={13}/>打开缓存位置</GlassButton></div>{record.tracks.length>0&&<div><b>关联音频</b><ul>{record.tracks.slice(0,5).map((track,i)=><li key={i}>{track.name}</li>)}</ul>{record.audioCount>5&&<p>共 {record.audioCount} 个音频，按原处理顺序保存。</p>}</div>}</div>}
        </article>)}
      </div>
      <div className="history-footer">
        {error&&<p className="history-feedback error" role="alert">{error}</p>}{message&&<p className="history-feedback" role="status">{message}</p>}
        {pending?<div className="history-confirm" role="alert"><h3>删除 {pending.length} 条历史记录？</h3><p>将删除选中任务的对应缓存和断点进度。小说、音频和已导出的文件保留。</p><div><GlassButton className="secondary" disabled={!!busy} onClick={()=>setPending(null)}>取消</GlassButton><GlassButton className="danger" disabled={!!busy||running} onClick={()=>void remove()}>{busy==='delete'?<LoaderCircle className="spin" size={14}/>:<Trash2 size={14}/>}确认删除</GlassButton></div></div>:
          managing?<div className="history-batch-footer"><span>{running?'处理期间可查看和导出，暂停后可删除。':'删除记录会同时清理该任务的缓存。'}</span><GlassButton className="danger secondary" disabled={!!busy||running||!chosen.size} onClick={()=>{setError('');setMessage('');setPending([...chosen])}}><Trash2 size={14}/>删除所选{chosen.size>0&&`（${chosen.size}）`}</GlassButton></div>:<p className="history-note">直接读取识别缓存；重新导出无需原音频或 GPU 环境。</p>}
      </div>
    </motion.section>
  </motion.div>
}
