const {app,BrowserWindow,ipcMain,dialog,shell,protocol,net}=require('electron')
const fs=require('node:fs'),fsp=fs.promises,path=require('node:path'),crypto=require('node:crypto')
const {spawn}=require('node:child_process')
const {pathToFileURL}=require('node:url')
const environmentStore=require('./environment-store.cjs')
const environmentPaths=require('./environment-paths.cjs')
const {setupProgress}=require('./setup-progress.cjs')
const historyStore=require('./history-store.cjs')
const appData=require('./app-data.cjs')
protocol.registerSchemesAsPrivileged([{scheme:'tingye-media',privileges:{standard:true,secure:true,stream:true,supportFetchAPI:true}}])
// Keep the legacy location for migration without using it for runtime storage.
const legacyData=path.join(app.getPath('appData'),app.getName())
const dataDirectory=app.isPackaged?appData.location(app.getPath('exe')):path.resolve(process.env.TINGYE_ALIGNER_DATA||path.join(__dirname,'../data'))
fs.mkdirSync(dataDirectory,{recursive:true})
app.setPath('userData',dataDirectory)
app.setPath('sessionData',path.join(dataDirectory,'chromium'))
app.setPath('crashDumps',path.join(dataDirectory,'crashes'))
app.setAppLogsPath(path.join(dataDirectory,'logs'))
const primary=app.requestSingleInstanceLock()
if(!primary)app.quit()
const dataInitialization=primary&&app.isPackaged?appData.prepare(legacyData,dataDirectory).then(()=>null,error=>error):Promise.resolve(null)
const root=app.isPackaged?process.resourcesPath:path.resolve(__dirname,'..')
const resource=name=>path.join(root,app.isPackaged?'':'resources',name)
let window,worker,stopping,allowClose=false,latestExport=null,persistTimer
let maintaining=false,preparingEnvironment=false,probePromise=null,historyBusy=false
let memoryBudgetGiB=6,settingsWrite=Promise.resolve()
let locations
const selected=new Set(),media=new Map()
const state={status:'idle',message:'准备好开始',progress:0,completed:0,total:0,matched:0,processedSeconds:0,totalSeconds:0,track:'',preview:[]}
const audioTypes=new Set(['.mp3','.m4a','.m4b','.wav','.flac','.ogg','.opus','.aac'])
const cache=()=>path.join(app.getPath('userData'),'jobs')

function publish(update){
  if(update.tracks){state.trackFiles=update.tracks.filter(item=>selected.has(item.path));for(const item of state.trackFiles)media.set(item.id,item.path);delete update.tracks}
  if(update.result&&!path.resolve(update.result).startsWith(path.resolve(cache())+path.sep))delete update.result
  Object.assign(state,update)
  if(window&&!window.isDestroyed())window.webContents.send('tingye:state',{...state})
  clearTimeout(persistTimer)
  persistTimer=setTimeout(()=>fsp.writeFile(path.join(app.getPath('userData'),'last-state.json'),JSON.stringify(state)).catch(()=>{}),500)
}
const python=()=>environmentPaths.pythonAt(locations.runtimeDir)
function localConfig(){try{return JSON.parse(fs.readFileSync(resource('local-engine.json'),'utf8'))}catch{return {}}}
const managedRoots=()=>environmentStore.roots({managedRuntime:locations.runtimeDir,models:locations.modelsDir},app.getPath('userData'),[root,app.getAppPath()],false)
const workerPath=()=>path.join(root,'engine','worker.py')
const models=()=>locations.modelsDir
const pathContext=()=>({data:app.getPath('userData'),protectedPaths:[cache(),root,app.getAppPath(),...selected]})
function invokePython(args,input,onEvent,timeout=0,onDiagnostic){
  const executable=python()
  if(!executable)return Promise.reject(new Error('请先准备识别组件'))
  return new Promise((resolve,reject)=>{
    const child=spawn(executable,['-u',workerPath(),...args],{windowsHide:true,stdio:['pipe','pipe','pipe'],env:{...process.env,PYTHONIOENCODING:'utf-8',PYTHONUTF8:'1'}})
    if(input||args[0]==='--prepare')worker=child
    let text='',last={},error='',timer,diagnostic='',diagnosticTime=0
    child.stdout.setEncoding('utf8');child.stderr.setEncoding('utf8')
    child.stdout.on('data',part=>{text+=part;let at;while((at=text.indexOf('\n'))>=0){const line=text.slice(0,at);text=text.slice(at+1);try{last=JSON.parse(line);onEvent?.(last)}catch{}}})
    child.stderr.on('data',part=>{
      error=(error+part).slice(-3000)
      if(onDiagnostic){
        diagnostic+=part
        const lines=diagnostic.split(/[\r\n]+/);diagnostic=lines.pop().slice(-1200)
        const line=lines.map(row=>row.replace(/\x1b\[[0-?]*[ -/]*[@-~]/g,'').trim()).filter(Boolean).pop()
        if(line&&Date.now()-diagnosticTime>=400){onDiagnostic(line.slice(-600));diagnosticTime=Date.now()}
      }
    })
    child.on('error',reject)
    child.on('close',code=>{clearTimeout(timer);if(worker===child)worker=null;code===0?resolve(last):reject(new Error(last.error||error.trim().slice(-900)||`识别进程退出：${code}`))})
    if(timeout)timer=setTimeout(()=>{child.kill();reject(new Error('检查识别环境超时，请重试'))},timeout)
    if(input)child.stdin.end(JSON.stringify(input)+'\n');else child.stdin.end()
  })
}
async function environment(){
  if(maintaining)return {ready:false,device:'',message:'正在更新识别环境',removable:false}
  if(probePromise)return probePromise
  probePromise=(async()=>{
    const removable=(await managedRoots()).length>0
    if(!python())return {ready:false,componentsReady:false,device:'',message:'当前目录尚未安装识别组件',removable,memoryBudgetGiB}
    try{
      const info=await invokePython(['--probe'],null,null,60000),hasModel=environmentPaths.modelReady(models())
      return {...info,ready:info.ready&&hasModel,message:info.ready&&!hasModel?'识别组件已就绪，需下载语音模型':info.message,removable,memoryBudgetGiB}
    }catch(error){return {ready:false,componentsReady:false,device:'',message:error.message,removable,memoryBudgetGiB}}
  })().finally(()=>{probePromise=null})
  return probePromise
}
async function collect(folder,depth=0){
  if(depth>12)throw new Error('音频目录层级过深')
  const output=[]
  for(const entry of await fsp.readdir(folder,{withFileTypes:true})){
    if(entry.isSymbolicLink())continue
    const target=path.join(folder,entry.name)
    if(entry.isDirectory())output.push(...await collect(target,depth+1))
    else if(entry.isFile()&&audioTypes.has(path.extname(entry.name).toLowerCase()))output.push(target)
    if(output.length>5000)throw new Error('每次最多处理 5000 个音频')
  }
  return output.sort((a,b)=>a.localeCompare(b,'zh-CN',{numeric:true,sensitivity:'base'}))
}
function trusted(event){
  if(event.sender!==window?.webContents||event.senderFrame!==window.webContents.mainFrame)throw new Error('无效调用来源')
}
function handle(name,callback){ipcMain.handle('tingye:'+name,async(event,...args)=>{trusted(event);return callback(...args)})}

app.whenReady().then(async()=>{
  if(!primary)return
  const dataError=await dataInitialization
  if(dataError){dialog.showErrorBox('数据目录无法使用','未能完成数据迁移，请保留原有文件并关闭正在使用旧版的程序后重试。\n\n'+dataError.message);app.quit();return}
  await fsp.mkdir(cache(),{recursive:true})
  locations=await environmentPaths.load(app.getPath('userData'),app.isPackaged?{}:localConfig())
  try{const saved=JSON.parse(await fsp.readFile(path.join(app.getPath('userData'),'preferences.json'),'utf8'));if(Number.isInteger(saved.memoryBudgetGiB)&&saved.memoryBudgetGiB>=1&&saved.memoryBudgetGiB<=32)memoryBudgetGiB=saved.memoryBudgetGiB}catch{}
  try {
    const saved=JSON.parse(await fsp.readFile(path.join(app.getPath('userData'),'last-state.json'),'utf8'))
    for(const source of [saved.sources?.novel,...(saved.sources?.audio||[])].filter(Boolean))if(fs.existsSync(source.path))selected.add(source.path)
    if(saved.sources?.novel&&!selected.has(saved.sources.novel.path))saved.sources.novel=null
    if(saved.sources)saved.sources.audio=saved.sources.audio.filter(source=>selected.has(source.path))
    if(saved.result&&(!path.resolve(saved.result).startsWith(path.resolve(cache())+path.sep)||!fs.existsSync(saved.result)))delete saved.result
    if(['running','preparing','pausing'].includes(saved.status)){saved.status='paused';saved.message='上次任务已保存，可继续处理'}
    Object.assign(state,saved)
    for(const item of saved.trackFiles||[])if(selected.has(item.path))media.set(item.id,item.path)
  }catch{}
  protocol.handle('tingye-media',async request=>{
    const id=new URL(request.url).hostname
    const source=media.get(id)
    if(!source)return new Response('Not found',{status:404})
    return net.fetch(pathToFileURL(source).href,{headers:request.headers})
  })
  window=new BrowserWindow({width:1120,height:860,minWidth:760,minHeight:650,show:false,frame:false,
    backgroundColor:'#edf1ed',title:'听页听读工坊',icon:path.join(root,'build','icon.png'),
    webPreferences:{preload:path.join(__dirname,'preload.cjs'),contextIsolation:true,nodeIntegration:false,sandbox:true}})
  window.webContents.setWindowOpenHandler(()=>({action:'deny'}))
  window.webContents.on('will-navigate',event=>event.preventDefault())
  window.once('ready-to-show',()=>window.show())
  handle('choose',async kind=>{
    if(worker||historyBusy)throw new Error('请先等待当前操作完成')
    if(!['novel','audio','folder'].includes(kind))throw new Error('无效选择类型')
    const result=await dialog.showOpenDialog(window,kind==='folder'?{title:'选择有声书文件夹',properties:['openDirectory']}:
      {title:kind==='novel'?'选择小说原文':'选择有声书音频',properties:kind==='novel'?['openFile']:['openFile','multiSelections'],
       filters:[{name:kind==='novel'?'小说文件':'音频文件',extensions:kind==='novel'?['epub','txt','md','html','htm','xhtml']:Array.from(audioTypes,x=>x.slice(1))}]})
    if(result.canceled)return []
    const files=kind==='folder'?await collect(result.filePaths[0]):result.filePaths
    const entries=[]
    for(const file of files){const stat=await fsp.stat(file);if(!stat.isFile())continue;selected.add(file);entries.push({path:file,name:path.basename(file),size:stat.size})}
    const sources=state.sources||{novel:null,audio:[]}
    if(kind==='novel')sources.novel=entries[0]||null;else sources.audio=entries
    publish({sources,status:'idle',message:'准备建立对应',result:undefined,progress:0,matched:0,preview:[],total:0,totalSeconds:0,processedSeconds:0,coverage:undefined,error:undefined})
    return entries
  })
  handle('environment',environment)
  handle('environmentPaths',()=>({...locations}))
  async function changeLocations(kind){
    if(worker||maintaining||preparingEnvironment)throw new Error('请先暂停处理或等待当前操作完成')
    maintaining=true
    try{
      if(probePromise)await probePromise
      let next
      if(kind==='default')next=environmentPaths.defaults(app.getPath('userData'))
      else{
        if(!['runtime','models'].includes(kind))throw new Error('目录类型无效')
        const field=kind==='runtime'?'runtimeDir':'modelsDir',current=locations[field]
        const answer=await dialog.showOpenDialog(window,{title:kind==='runtime'?'选择运行组件目录':'选择语音模型目录',buttonLabel:'使用此目录',defaultPath:fs.existsSync(current)?current:path.dirname(current),properties:['openDirectory','createDirectory']})
        if(answer.canceled||!answer.filePaths.length)return null
        next={...locations,[field]:answer.filePaths[0]}
      }
      locations=await environmentPaths.configure(app.getPath('userData'),next,pathContext())
      return {...locations}
    }finally{maintaining=false}
  }
  handle('chooseEnvironmentPath',changeLocations)
  handle('setMemoryBudget',async value=>{
    if(!Number.isInteger(value)||value<1||value>32)throw new Error('显存预算须为 1 至 32 GB')
    memoryBudgetGiB=value
    settingsWrite=settingsWrite.catch(()=>{}).then(async()=>{
      const file=path.join(app.getPath('userData'),'preferences.json'),temporary=file+'.tmp'
      await fsp.writeFile(temporary,JSON.stringify({memoryBudgetGiB}));await fsp.rename(temporary,file)
    })
    await settingsWrite
  })
  handle('state',()=>({...state}))
  handle('prepare',async()=>{
    if(worker||maintaining||preparingEnvironment)throw new Error('请先等待当前操作完成')
    preparingEnvironment=true
    maintaining=true
    publish({status:'preparing',message:'正在准备识别组件',setupDetail:'正在检查已有组件',error:undefined})
    try{
      if(probePromise)await probePromise
      await environmentPaths.validate(locations,pathContext())
      maintaining=false
      const env=await environment()
      if(!env.componentsReady){
        await environmentStore.mark(locations.runtimeDir,'runtime')
        await new Promise((resolve,reject)=>{
          const child=spawn('powershell.exe',['-NoProfile','-ExecutionPolicy','RemoteSigned','-File',path.join(root,'scripts','prepare-engine.ps1'),'-RuntimeRoot',locations.runtimeDir,'-Requirements',path.join(root,'engine','requirements.txt')],{windowsHide:true,stdio:['ignore','pipe','pipe']})
          worker=child
          let error='',output=''
          const stages={'Downloading Python runtime':'正在下载 Python 运行组件','Installing pip':'正在安装组件管理工具','Installing GPU inference components':'正在安装 GPU 推理组件，首次下载较大','Installing speech recognition components':'正在安装语音识别组件','Using Tsinghua Python package mirror':'正在安装语音识别组件','Retrying with official Python package index':'正在切换备用下载源'}
          child.stdout.setEncoding('utf8');child.stderr.setEncoding('utf8')
          child.stderr.on('data',part=>error=(error+part).slice(-2400))
          child.stdout.on('data',part=>{output+=part;let at;while((at=output.indexOf('\n'))>=0){const line=output.slice(0,at);output=output.slice(at+1);try{const status=JSON.parse(line);if(stages[status.message])publish({message:stages[status.message],setupDetail:status.message==='Using Tsinghua Python package mirror'?'下载源：清华大学开源软件镜像站':status.message==='Retrying with official Python package index'?'镜像源未完成安装，正在使用 PyPI 重试':'正在检查并复用已有组件'});else if(status.detail||status.waitingSeconds)publish({setupDetail:setupProgress(status,state.setupDetail)})}catch{}}})
          child.on('error',error=>{if(worker===child)worker=null;reject(error)})
          child.on('close',code=>{if(worker===child)worker=null
            const message=error.trim().replace('Python runtime validation failed','Python 运行组件校验失败').replace('pip installation failed','组件管理工具安装失败').replace('pip validation failed','组件管理工具校验失败').replace('GPU component installation failed','GPU 推理组件安装失败').replace('Speech component installation failed','语音识别组件安装失败')
            code===0?resolve():reject(new Error(message||'识别组件准备失败，请检查网络后重试'))
          })
        })
      }
      publish({message:'正在准备语音模型，首次使用会下载模型',setupDetail:'语音依赖已就绪，正在下载或加载 SenseVoice 模型'})
      await environmentStore.mark(models(),'models')
      await invokePython(['--prepare',models()],null,null,0,line=>publish({setupDetail:line}))
      publish({status:'idle',message:'识别环境已就绪',setupDetail:undefined})
    }catch(error){publish({status:'error',error:error.message,message:'识别组件准备失败'});throw error}
    finally{maintaining=false;preparingEnvironment=false}
  })
  handle('removeEnvironment',async()=>{
    if(worker||maintaining||preparingEnvironment)throw new Error('请先暂停识别或等待组件准备完成')
    const entries=await managedRoots()
    if(!entries.length)throw new Error('没有可删除的本工具识别环境')
    const answer=await dialog.showMessageBox(window,{type:'question',title:'删除 GPU 识别环境',message:'删除识别组件和语音模型？',detail:'对应文件、任务进度、小说和音频会保留。下次识别需要重新下载组件。\n\n'+entries.map(entry=>entry.root).join('\n'),buttons:['取消','删除环境'],defaultId:0,cancelId:0})
    if(answer.response!==1)return false
    if(worker||maintaining||preparingEnvironment)throw new Error('当前有任务运行，请暂停后再删除')
    maintaining=true
    try{
      if(probePromise)await probePromise
      await environmentStore.remove(entries,[app.getPath('userData'),root,app.getAppPath()])
      return true
    }finally{maintaining=false}
  })
  handle('start',async(novel,audio)=>{
    if(worker||maintaining||preparingEnvironment||historyBusy)throw new Error('已有任务正在运行或正在管理缓存，请等待当前操作完成')
    if(typeof novel!=='string'||!selected.has(novel)||!Array.isArray(audio)||!audio.length||audio.length>5000||audio.some(p=>typeof p!=='string'||!selected.has(p)))throw new Error('请重新选择小说和音频')
    const source=file=>({path:file,name:path.basename(file),size:fs.statSync(file).size})
    const id=crypto.randomUUID(),stopFile=path.join(cache(),id+'.stop');stopping=stopFile
    publish({sources:{novel:source(novel),audio:audio.map(source)},status:'preparing',message:'正在准备任务',error:undefined,result:undefined,progress:0,matched:0,preview:[],phase:'recognition',track:'',setupDetail:undefined,speed:undefined,batchSize:undefined,vramGiB:undefined,verificationDone:undefined,verificationTotal:undefined,verificationAdded:undefined})
    invokePython([], {novel,audio,cache_root:cache(),models_root:models(),ffmpeg:resource('bin/ffmpeg.exe'),stop_file:stopFile,gpu_memory_gib:memoryBudgetGiB},publish)
      .catch(error=>publish({status:'error',message:'处理未完成',error:error.message}))
      .finally(()=>{fsp.unlink(stopFile).catch(()=>{});if(stopping===stopFile)stopping=null})
  })
  handle('pause',async()=>{if(worker&&stopping){publish({status:'pausing',message:'正在保存已完成结果'});await fsp.writeFile(stopping,'pause')}})
  handle('export',async()=>{
    if(!state.result||worker||historyBusy)throw new Error('请先暂停或等待当前操作完成')
    return exportHistory(path.basename(path.dirname(state.result)))
  })
  async function exportHistory(id){
    if(historyBusy)throw new Error('请先等待当前导出或删除操作完成')
    historyBusy=true
    try{
      const record=await historyStore.result(cache(),id)
      const title=record.title.replace(/[<>:"/\\|?*\x00-\x1f]/g,'_').slice(0,100)||'听读对应'
      const answer=await dialog.showSaveDialog(window,{title:'导出听读对应文件',defaultPath:title+'.tysync',filters:[{name:'听页逐句对应文件',extensions:['tysync']}]})
      if(answer.canceled||!answer.filePath)return null
      latestExport=await historyStore.copy(cache(),id,answer.filePath)
      return latestExport
    }finally{historyBusy=false}
  }
  handle('history',()=>historyStore.list(cache()))
  handle('exportHistory',exportHistory)
  handle('revealHistory',async id=>{const record=await historyStore.entry(cache(),id);shell.showItemInFolder(record.cachePath)})
  handle('removeHistory',async ids=>{
    if(worker||maintaining||preparingEnvironment||historyBusy)throw new Error('请先暂停处理，并等待当前操作完成后再删除历史记录')
    historyBusy=true
    try{
      const outcome=await historyStore.remove(cache(),ids,[...selected])
      if(state.result&&(outcome.deleted.includes(path.basename(path.dirname(state.result)))||!fs.existsSync(state.result)))publish({status:'idle',message:'此任务缓存已删除，可重新建立对应',result:undefined,preview:[],matched:0,completed:0,total:0,progress:0,coverage:undefined,processedSeconds:0,totalSeconds:0})
      return outcome
    }finally{historyBusy=false}
  })
  handle('reveal',async()=>{if(latestExport)shell.showItemInFolder(latestExport)})
  handle('media',id=>{if(typeof id!=='string'||!media.has(id))throw new Error('音频未关联');return 'tingye-media://'+id+'/audio'})
  ipcMain.on('tingye:window',(event,action)=>{trusted(event);if(action==='minimize')window.minimize();else if(action==='maximize')window.isMaximized()?window.unmaximize():window.maximize();else if(action==='close')window.close()})
  window.on('close',async event=>{
    if(maintaining||(preparingEnvironment&&!worker)){event.preventDefault();return}
    if(allowClose||!worker)return
    event.preventDefault()
    const running=worker,stopFile=stopping
    if(!stopFile){
      const answer=await dialog.showMessageBox(window,{type:'question',title:'正在准备识别组件',message:'停止准备并退出？',detail:'下次打开后可以重新准备识别组件。',buttons:['继续准备','停止并退出'],defaultId:0,cancelId:0})
      if(answer.response===1){if(worker===running)spawn('taskkill',['/PID',String(running.pid),'/T','/F'],{windowsHide:true});allowClose=true;window.close()}
      return
    }
    const answer=await dialog.showMessageBox(window,{type:'question',title:'正在建立对应',message:'暂停并退出？',detail:'已完成片段会保留，下次可以继续。',buttons:['继续处理','暂停并退出'],defaultId:0,cancelId:0})
    if(answer.response===1){
      if(worker!==running){allowClose=true;window.close();return}
      await fsp.writeFile(stopFile,'pause')
      running.once('close',()=>{allowClose=true;window.close()})
      setTimeout(()=>{if(worker===running){running.kill();allowClose=true;window.close()}},30000)
    }
  })
  await window.loadFile(path.join(app.getAppPath(),'dist','index.html'))
  // Electron may create an empty default profile during native startup. Remove
  // only that empty directory; never remove legacy files that still need review.
  if(app.isPackaged)await fsp.rmdir(legacyData).catch(()=>{})
})
app.on('second-instance',()=>{if(window){if(window.isMinimized())window.restore();window.focus()}})
app.on('window-all-closed',()=>app.quit())
