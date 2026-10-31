const fs=require('node:fs/promises'),path=require('node:path')
const marker='tingye-environment.json'
const identity='com.tingye.aligner.environment.v1'
const key=value=>path.resolve(value).toLowerCase()
async function owned(root,kind,protectedPaths=[]){
  if(!root)return false
  const target=path.resolve(root)
  if(target===path.parse(target).root||protectedPaths.some(p=>key(p)===key(target)||key(p).startsWith(key(target)+path.sep)))return false
  try{
    const stat=await fs.lstat(target)
    if(!stat.isDirectory()||stat.isSymbolicLink()||key(await fs.realpath(target))!==key(target))return false
    const data=JSON.parse(await fs.readFile(path.join(target,marker),'utf8'))
    return data.owner===identity&&data.kind===kind
  }catch{return false}
}
async function roots(config,userData,protectedPaths=[],includeDefaults=true){
  const candidates=[
    ...(includeDefaults?[[path.join(userData,'engine'),'runtime'],[path.join(userData,'models'),'models']]:[]),
    [config.managedRuntime,'runtime'],[config.models,'models']
  ]
  const output=[]
  for(const [root,kind] of candidates)if(await owned(root,kind,[userData,...protectedPaths])){
    const resolved=path.resolve(root)
    if(!output.some(entry=>key(entry.root)===key(resolved)))output.push({root:resolved,kind})
  }
  return output
}
async function remove(entries,protectedPaths=[]){
  // Check every target before deleting the first one; never follow a junction.
  for(const entry of entries)if(!await owned(entry.root,entry.kind,protectedPaths))throw new Error('识别组件目录校验失败，未执行删除')
  for(const entry of entries)try{await fs.rm(entry.root,{recursive:true,force:true,maxRetries:4,retryDelay:250})}catch(error){
    const stat=await fs.lstat(entry.root).catch(()=>null)
    if(stat?.isDirectory()&&!stat.isSymbolicLink())await mark(entry.root,entry.kind)
    throw new Error('识别环境仍被占用或无法删除，请关闭相关程序后重试：'+error.message)
  }
}
async function mark(root,kind){
  await fs.mkdir(root,{recursive:true})
  await fs.writeFile(path.join(root,marker),JSON.stringify({owner:identity,kind}))
}
module.exports={roots,remove,mark,owned}
