const fs=require('node:fs'),fsp=fs.promises,path=require('node:path'),crypto=require('node:crypto')
const store=require('./environment-store.cjs')
const key=value=>path.resolve(value).toLowerCase()
const contains=(parent,child)=>key(parent)===key(child)||key(child).startsWith(key(parent)+path.sep)
const overlap=(a,b)=>contains(a,b)||contains(b,a)
const defaults=data=>({runtimeDir:path.join(data,'engine'),modelsDir:path.join(data,'models')})
const filename=data=>path.join(data,'environment.json')
function normalize(value){
  if(!value||!['runtimeDir','modelsDir'].every(k=>typeof value[k]==='string'&&path.isAbsolute(value[k])))throw new Error('识别环境目录配置无效')
  const result={runtimeDir:path.resolve(value.runtimeDir),modelsDir:path.resolve(value.modelsDir)}
  if(Object.values(result).some(p=>key(p)===key(path.parse(p).root))||overlap(result.runtimeDir,result.modelsDir))throw new Error('组件目录与模型目录必须分开，不能互相包含或使用磁盘根目录')
  return result
}
function pythonAt(folder){
  return [path.join(folder,'python.exe'),path.join(folder,'Scripts','python.exe')].find(p=>{try{return fs.statSync(p).isFile()}catch{return false}})||null
}
async function save(data,value){
  const config=normalize(value);await fsp.mkdir(data,{recursive:true})
  const temporary=filename(data)+'.'+crypto.randomUUID()+'.tmp'
  try{await fsp.writeFile(temporary,JSON.stringify(config,null,2));await fsp.rename(temporary,filename(data))}
  finally{await fsp.unlink(temporary).catch(()=>{})}
  return config
}
async function load(data,legacy={}){
  try{return normalize(JSON.parse(await fsp.readFile(filename(data),'utf8')))}catch(error){if(error.code!=='ENOENT')return defaults(data)}
  const result=defaults(data)
  // Import an existing owned installation once; never embed machine paths in installers.
  if(legacy.managedRuntime&&legacy.python&&contains(legacy.managedRuntime,legacy.python)&&fs.existsSync(legacy.python)&&await store.owned(legacy.managedRuntime,'runtime'))result.runtimeDir=legacy.managedRuntime
  if(legacy.models&&await store.owned(legacy.models,'models'))result.modelsDir=legacy.models
  const config=normalize(result)
  if(config.runtimeDir!==defaults(data).runtimeDir||config.modelsDir!==defaults(data).modelsDir)await save(data,config)
  return config
}
async function validateDirectory(folder,kind,{data,protectedPaths=[]}){
  if(contains(folder,data)||protectedPaths.some(p=>overlap(folder,p)))throw new Error('该位置包含应用程序或收听数据，请选择独立的组件目录')
  // Reject junctions and paths beneath junctions, including for not-yet-created folders.
  let existing=folder
  while(!fs.existsSync(existing)){
    const parent=path.dirname(existing);if(parent===existing)throw new Error('目录所在磁盘不可用');existing=parent
  }
  if(key(await fsp.realpath(existing))!==key(existing)||(await fsp.lstat(existing)).isSymbolicLink())throw new Error('请选择普通文件夹，不支持链接或映射目录')
  if(fs.existsSync(folder)){
    if(!(await fsp.stat(folder)).isDirectory())throw new Error('请选择文件夹')
    if((await fsp.readdir(folder)).length&&!await store.owned(folder,kind))throw new Error('请选择空文件夹，或本工具已创建的同类组件文件夹')
  }else await fsp.mkdir(folder,{recursive:true})
  const probe=path.join(folder,'.tingye-write-'+crypto.randomUUID())
  try{await fsp.writeFile(probe,'',{flag:'wx'})}catch{throw new Error('该目录无法写入，请选择有写入权限的文件夹')}
  finally{await fsp.unlink(probe).catch(()=>{})}
}
async function validate(value,context){
  const config=normalize(value)
  await validateDirectory(config.runtimeDir,'runtime',context)
  await validateDirectory(config.modelsDir,'models',context)
  return config
}
async function configure(data,value,context){
  const config=await validate(value,{...context,data})
  return save(data,config)
}
function modelReady(folder){
  const directory=path.join(folder,'models','iic--SenseVoiceSmall','snapshots','master')
  return ['model.pt','config.yaml'].every(name=>{try{return fs.statSync(path.join(directory,name)).size>0}catch{return false}})
}
module.exports={defaults,load,save,configure,validate,pythonAt,modelReady}
