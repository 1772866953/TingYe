import { useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import { AudioLines, ArrowUpRight, BookOpen, Check, Cpu, Download, FileText, FolderOpen, Headphones, History, LoaderCircle, Minus, Pause, Play, Settings2, Square, Trash2, X } from 'lucide-react'
import HistoryDialog from './HistoryDialog'
import {version} from '../package.json'
import { AppearanceContext, GlassButton, GlassSlider, GlassSurface } from './glass'
import type { Environment, EnvironmentPaths, JobState, Source, Preview } from './api'
import './glass.css'

const initial: JobState = {status:'idle',message:'准备好开始',progress:0,completed:0,total:0,matched:0,processedSeconds:0,totalSeconds:0,track:'',preview:[]}
const time = (ms: number) => {const s=Math.floor(ms/1000);return `${Math.floor(s/60)}:${String(s%60).padStart(2,'0')}`}
const duration = (seconds: number) => seconds >= 3600 ? `${(seconds/3600).toFixed(1)} 小时` : `${Math.ceil(seconds/60)} 分钟`
const spring = {type:'spring' as const,stiffness:390,damping:34,mass:.8}
const errorMessage=(error:unknown)=>String(error instanceof Error?error.message:error).replace(/^Error:\s*/,'').replace(/^Error invoking remote method '[^']+':\s*(?:Error:\s*)?/,'')

export default function App() {
  const reduced=!!useReducedMotion()
  const [novel,setNovel]=useState<Source|null>(null),[audio,setAudio]=useState<Source[]>([])
  const [job,setJob]=useState(initial),[environment,setEnvironment]=useState<Environment>({ready:false,device:'',message:'正在检查识别环境'})
  const [settings,setSettings]=useState(false),[working,setWorking]=useState(false)
  const [history,setHistory]=useState(false)
  const [notice,setNotice]=useState(''),[exported,setExported]=useState(false),[playing,setPlaying]=useState<number|null>(null)
  const [memoryBudget,setMemoryBudget]=useState(6)
  const [environmentPaths,setEnvironmentPaths]=useState<EnvironmentPaths|null>(null),[pathNotice,setPathNotice]=useState('')
  const player=useRef<HTMLAudioElement|null>(null)
  const running=['running','preparing','pausing'].includes(job.status)
  useEffect(()=>{
    window.tingye.environment().then(value=>{setEnvironment(value);setMemoryBudget(value.memoryBudgetGiB??6)}).catch(e=>setNotice(String(e)))
    window.tingye.environmentPaths().then(setEnvironmentPaths).catch(e=>setNotice(String(e)))
    window.tingye.state().then(state=>{setJob(state);if(state.sources){setNovel(state.sources.novel);setAudio(state.sources.audio)}})
    return window.tingye.subscribe(setJob)
  },[])
  useEffect(()=>()=>{player.current?.pause()},[])
  async function choose(kind:'novel'|'audio'|'folder') {
    try {const files=await window.tingye.choose(kind);if(!files.length)return
      if(kind==='novel')setNovel(files[0]);else setAudio(files)
      setExported(false);setNotice('')
    } catch(e){setNotice(String(e))}
  }
  async function start() {
    if(!novel||!audio.length)return
    setWorking(true);setNotice('');setExported(false)
    try{await window.tingye.start(novel.path,audio.map(x=>x.path))}catch(e){setNotice(String(e))}finally{setWorking(false)}
  }
  async function prepare() {
    setWorking(true);setNotice('');setPathNotice('')
    try{await window.tingye.prepare();setEnvironment(await window.tingye.environment())}catch(e){const message=errorMessage(e);setNotice(message);setPathNotice(message)}finally{setWorking(false)}
  }
  async function removeEnvironment() {
    setWorking(true);setNotice('')
    try{if(await window.tingye.removeEnvironment()){setEnvironment(await window.tingye.environment());setNotice('识别组件与模型已删除，小说、音频和对应结果保留。')}}catch(e){setNotice(String(e))}finally{setWorking(false)}
  }
  async function changeEnvironmentPath(kind:'runtime'|'models'|'default') {
    setWorking(true);setPathNotice('')
    try{
      const paths=await window.tingye.chooseEnvironmentPath(kind)
      if(paths){setEnvironmentPaths(paths);setEnvironment(await window.tingye.environment());setPathNotice('目录已保存。原目录文件保留，新目录缺少组件时请点击「准备识别组件」。')}
    }catch(e){setPathNotice(errorMessage(e))}finally{setWorking(false)}
  }
  async function save() {try {const path=await window.tingye.export();if(path){setExported(true);setNotice('对应文件已导出，可传到手机后导入。')}}catch(e){setNotice(String(e))}}
  async function listen(row: Preview,index:number) {
    if(playing===index){player.current?.pause();setPlaying(null);return}
    player.current?.pause()
    try {
      const element=new Audio(await window.tingye.media(row.audio));player.current=element
      element.currentTime=Math.max(0,row.t0/1000-.12)
      element.ontimeupdate=()=>{if(element.currentTime>=row.t1/1000+.2){element.pause();setPlaying(null)}}
      element.onended=()=>setPlaying(null);element.onerror=()=>{setPlaying(null);setNotice('此音频暂时无法试听')}
      await element.play();setPlaying(index)
    } catch(e){setNotice(String(e));setPlaying(null)}
  }
  const transition=reduced?{duration:0}:spring
  return <AppearanceContext.Provider value={{dark:false,reduced}}>
    <main className="app">
      <div className="ambient ambient-a"/><div className="ambient ambient-b"/>
      <header className="titlebar"><div className="brand"><span className="brand-icon"><BookOpen size={18}/></span><span>听页<span className="brand-divider">/</span><span className="brand-sub">听读工坊</span></span></div>
        <div className="window-actions"><GlassButton title="最小化" aria-label="最小化" onClick={()=>window.tingye.window('minimize')}><Minus size={14}/></GlassButton><GlassButton title="最大化" aria-label="最大化" onClick={()=>window.tingye.window('maximize')}><Square size={11}/></GlassButton><GlassButton title="关闭" aria-label="关闭窗口" className="close-window" onClick={()=>window.tingye.window('close')}><X size={15}/></GlassButton></div>
      </header>
      <div className="content-scroll"><div className="workspace">
        <div className="heading"><div><div className="eyebrow"><span/>本地处理 · 逐句对应</div><h1>听读对应文件</h1><p>选择小说和有声书，生成可在手机上使用的对应文件。</p></div>
          <div className="heading-actions"><GlassButton className="icon-button" radius={999} aria-label="历史记录" title="历史记录" onClick={()=>setHistory(true)}><History size={19}/></GlassButton><GlassButton className="icon-button settings-button" radius={999} aria-label="处理设置" onClick={()=>setSettings(true)}><Settings2 size={18}/></GlassButton></div>
        </div>
        <section className="sources">
          <GlassSurface className="source-card" kind="dialog" radius={26} interactive={false}>
            <div className="source-top"><span className="source-icon"><FileText size={22}/></span><span className="step">01 / 小说原文</span>{novel&&<span className="check"><Check size={15}/></span>}</div>
            <h2>{novel?.name||'选择小说'}</h2><p className="source-path" title={novel?.path}>{novel?.path||'TXT、EPUB，也支持 HTML 和 Markdown'}</p>
            <GlassButton className="secondary" disabled={running||working} onClick={()=>choose('novel')}><BookOpen size={15}/>{novel?'更换小说':'选择小说'}</GlassButton>
          </GlassSurface>
          <GlassSurface className="source-card" kind="dialog" radius={26} interactive={false}>
            <div className="source-top"><span className="source-icon audio-icon"><Headphones size={22}/></span><span className="step">02 / 有声书音频</span>{audio.length>0&&<span className="check"><Check size={15}/></span>}</div>
            <h2>{audio.length?`${audio.length} 个音频文件`:'选择有声书'}</h2><p className="source-path">{audio.length?audio[0].name+(audio.length>1?' 等':''):'选择完整音频文件夹，按播放顺序排列'}</p>
            <div className="card-actions"><GlassButton className="secondary" disabled={running||working} onClick={()=>choose('folder')}><FolderOpen size={15}/>{audio.length?'更换文件夹':'选择文件夹'}</GlassButton><GlassButton className="text-action" disabled={running||working} onClick={()=>choose('audio')}>选择文件</GlassButton></div>
          </GlassSurface>
        </section>
        <GlassSurface className="process-card" kind="dialog" radius={26} interactive={false}>
          <div className="process-header"><div className="process-title"><span className={`process-orb ${running?'processing':''}`}>{job.status==='completed'?<Check size={19}/>:running?<AudioLines size={20}/>:<Cpu size={19}/>}</span><div><h3>{running?job.message:job.status==='completed'?'逐句对应已建立':job.status==='paused'?'已暂停，可继续处理':job.status==='error'?'处理未完成':'准备建立对应'}</h3><p>{running?job.track:job.status==='completed'?'已完成的句子可直接导入手机使用':job.status==='paused'?'已完成结果保留，再次开始会跳过已处理片段':environment.ready?`${environment.device} · 离线识别`:'首次使用需准备识别组件'}</p></div></div>
            <AnimatePresence mode="wait" initial={false}><motion.div key={running?'running':job.result?'export':'start'} initial={{opacity:0,scale:.96}} animate={{opacity:1,scale:1}} exit={{opacity:0,scale:.96}} transition={transition}>
              {running?<GlassButton className="secondary" disabled={working||job.status==='pausing'} onClick={()=>window.tingye.pause()}><Pause size={15}/>{working?'正在准备组件':job.status==='pausing'?'正在保存':'暂停处理'}</GlassButton>:job.result?<GlassButton className="primary" onClick={save}><Download size={16}/>{exported?'再次导出':'导出对应文件'}</GlassButton>:<GlassButton className="primary" disabled={working||!environment.ready||!novel||!audio.length} onClick={start}>{working?<LoaderCircle size={16} className="spin"/>:<Play size={15}/>}开始识别与核验</GlassButton>}
            </motion.div></AnimatePresence>
          </div>
          {job.status==='preparing'&&job.setupDetail&&<p className="setup-detail" role="status">{job.setupDetail}</p>}
          <div className="progress-track"><motion.div initial={false} animate={{scaleX:Math.max(0,Math.min(1,job.progress))}} transition={reduced?{duration:0}:{duration:.55,ease:[.22,.7,.25,1]}}/></div>
          <div className="process-footer"><span>{job.matched>0?<><b>{job.matched.toLocaleString()}</b> 句已对应{job.coverage!==undefined&&` · 正文覆盖 ${(job.coverage*100).toFixed(1)}%`}</>:'逐句时间索引 · 支持断点继续'}</span><span>{job.totalSeconds>0?`${duration(job.processedSeconds)} / ${duration(job.totalSeconds)}`:'全程在本机处理'}{job.total>0&&<b className="percentage">{(job.progress*100).toFixed(1)}%</b>}</span></div>
          {typeof job.speed==='number'&&<div className="performance-info">{job.batchSize} 段/批 · {job.speed.toFixed(0)} 倍实时速度{job.vramGiB!==undefined&&` · 显存峰值 ${job.vramGiB.toFixed(1)} GB`}</div>}
          {job.phase==='verification'&&job.verificationTotal!==undefined&&<div className="performance-info">二次核验 · {job.verificationDone??0}/{job.verificationTotal} 个音频片段 · 已补齐 {job.verificationAdded??0} 个短句</div>}
          {!running&&job.result&&<div className="resume-row"><GlassButton className="text-action" onClick={start} disabled={working||!environment.ready||!novel||!audio.length}>{job.status==='completed'?'核验并补齐短句':'继续识别与核验'}<ArrowUpRight size={13}/></GlassButton><span>复用已识别缓存，可暂停继续</span></div>}
          {!environment.ready&&!running&&<GlassButton className="environment-action" disabled={working} onClick={prepare}>{working?<LoaderCircle size={15} className="spin"/>:<Download size={15}/>}准备识别组件<span>首次使用需要联网下载</span></GlassButton>}
        </GlassSurface>
        <AnimatePresence>{notice&&<motion.div role="status" className="notice" initial={{opacity:0,y:5}} animate={{opacity:1,y:0}} exit={{opacity:0,y:5}} transition={transition}>{notice}<GlassButton aria-label="关闭提示" onClick={()=>setNotice('')}><X size={13}/></GlassButton></motion.div>}</AnimatePresence>
        {job.status==='error'&&<div role="alert" className="error-message">{job.error||job.message}</div>}
        <AnimatePresence initial={false}>{job.preview.length>0&&<motion.section className="preview" initial={{opacity:0,y:12}} animate={{opacity:1,y:0}} transition={transition}>
          <div className="section-title"><h3>短句预览</h3><span>点击试听核对位置</span></div>
          {job.preview.slice(-5).map((row,index)=><GlassButton key={`${row.audio}:${row.n0}`} className={`preview-row ${playing===index?'playing':''}`} onClick={()=>listen(row,index)}><span className="preview-play">{playing===index?<Pause size={13}/>:<Play size={13}/>}</span><span className="preview-text">{row.text}</span><span className="preview-time">{time(row.t0)}</span></GlassButton>)}
        </motion.section>}</AnimatePresence>
        <footer className="footnote"><span>生成后，将 .tysync 文件传到手机，在「听读同步」中导入。</span><span>听页听读工坊 {version}</span></footer>
      </div></div>
      <AnimatePresence>{settings&&<motion.div className="modal-overlay" initial={{opacity:0}} animate={{opacity:1}} exit={{opacity:0}} transition={{duration:reduced?0:.2}} onClick={()=>setSettings(false)}>
        <motion.section className="settings-dialog" role="dialog" aria-modal="true" aria-label="处理设置" initial={{opacity:0,scale:.94,y:16}} animate={{opacity:1,scale:1,y:0}} exit={{opacity:0,scale:.96,y:12,transition:{duration:reduced?0:.2,ease:[.4,0,.2,1]}}} transition={transition} onClick={e=>e.stopPropagation()}>
          <div className="section-title settings-header"><h2>处理设置</h2><GlassButton aria-label="关闭设置" radius={999} className="icon-button" onClick={()=>setSettings(false)}><X size={16}/></GlassButton></div>
          <div className="settings-scroll" role="region" aria-label="设置内容" tabIndex={0}>
          <div className="setting-item"><span>计算设备</span><b>{environment.device||'自动选择 NVIDIA GPU'}</b></div>
          <div className="setting-item"><span>处理方式</span><b>GPU 批量识别 · 逐字时间对齐</b></div>
          <div className="setting-item"><span>显存使用</span><b>按可用容量分批 · 不足时自动减小批量</b></div>
          <div className="memory-setting"><label htmlFor="memory-budget">显存预算 <output>{memoryBudget} GB</output></label>
            <GlassSlider id="memory-budget" aria-label="显存预算" min="1" max="32" step="1" value={memoryBudget}
              onChange={event=>setMemoryBudget(Number(event.target.value))}
              onPointerUp={event=>window.tingye.setMemoryBudget(Number(event.currentTarget.value)).catch(e=>setNotice(String(e)))}
              onKeyUp={event=>window.tingye.setMemoryBudget(Number(event.currentTarget.value)).catch(e=>setNotice(String(e)))}
              onBlur={event=>window.tingye.setMemoryBudget(Number(event.currentTarget.value)).catch(e=>setNotice(String(e)))}/>
            <div className="range-ends"><span>1 GB</span><span>32 GB</span></div>
            <p className="setting-note">下次识别生效。按设定预算和显卡实际可用容量分批处理，系统显示与驱动占用另计。</p>
          </div>
          <section className="environment-paths" aria-label="识别环境目录" aria-busy={working}>
            <div className="section-title"><h3>识别环境目录</h3><GlassButton className="text-action" disabled={working||running||!environmentPaths} onClick={()=>changeEnvironmentPath('default')}>恢复默认目录</GlassButton></div>
            <div className="environment-path-row"><div><h4>运行组件</h4><p data-testid="runtime-path" title={environmentPaths?.runtimeDir}>{environmentPaths?.runtimeDir||'正在读取目录…'}</p></div><GlassButton className="text-action" aria-label="更改运行组件目录" disabled={working||running||!environmentPaths} onClick={()=>changeEnvironmentPath('runtime')}><FolderOpen size={14}/>更改</GlassButton></div>
            <div className="environment-path-row"><div><h4>语音模型</h4><p data-testid="models-path" title={environmentPaths?.modelsDir}>{environmentPaths?.modelsDir||'正在读取目录…'}</p></div><GlassButton className="text-action" aria-label="更改语音模型目录" disabled={working||running||!environmentPaths} onClick={()=>changeEnvironmentPath('models')}><FolderOpen size={14}/>更改</GlassButton></div>
            <p className="setting-note">选择空文件夹或本工具已有的同类组件目录。更改位置不会移动或删除原文件，已生成对应及处理进度保留。</p>
            {pathNotice&&<p className="path-notice" role="status">{pathNotice}</p>}
          </section>
          <p className="setting-note">已匹配的句子会保存准确的音频位置。插话、删改和无法确认的内容会保留为空白，手机可使用大致同步。</p>
          <p className="setting-note">可以随时暂停。保持小说、音频和顺序一致，再次开始会继续处理。</p>
          <div className="environment-status"><Cpu size={16}/><span>{working?job.message:environment.message}</span></div>
          {working&&job.setupDetail&&<p className="setup-detail" role="status">{job.setupDetail}</p>}
          <div className="environment-buttons">
            {!environment.ready&&<GlassButton className="primary" disabled={working||running} onClick={prepare}>准备识别组件</GlassButton>}
            {environment.removable&&<GlassButton className="danger" disabled={working||running} onClick={removeEnvironment}><Trash2 size={15}/>{working?'正在处理':'删除识别环境'}</GlassButton>}
          </div>
          <p className="setting-note">仅删除当前目录中由本工具管理的运行组件和模型。已生成对应、处理进度、小说及音频保留；之后可重新安装组件。</p>
          <section className="version-info" aria-label="版本说明"><div className="section-title"><h3>版本说明</h3><span className="version-badge">{version}</span></div><p>听页听读工坊 · Windows 桌面版</p><ul><li>按逗号、分号、冒号等标点生成更细的短句定位。</li><li>识别完成后自动复查原文和时间证据，局部回查缺失短句并进行原句强制对齐。</li><li>广告、平台提示等额外语音保持未匹配，不将它们压入小说时间轴。</li><li>旧任务复用已有字符缓存；二次核验支持暂停与继续。</li><li>历史记录、安装目录 data 和原有设置保持兼容。</li></ul></section>
          </div>
        </motion.section>
      </motion.div>}</AnimatePresence>
      <AnimatePresence>{history&&<HistoryDialog running={running} onClose={()=>setHistory(false)}/>}</AnimatePresence>
    </main>
  </AppearanceContext.Provider>
}
