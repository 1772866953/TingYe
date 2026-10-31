const fs=require('node:fs'),fsp=fs.promises,path=require('node:path'),crypto=require('node:crypto')
const marker='.tingye-data.json',journal='.tingye-migration.json'
const key=p=>path.resolve(p).toLowerCase(),inside=(parent,child)=>key(child).startsWith(key(parent)+path.sep)
const owned=folder=>['last-state.json','environment.json','jobs','engine'].some(name=>fs.existsSync(path.join(folder,name)))
function location(executable){return path.join(path.dirname(path.resolve(executable)),'data')}
function rebase(value,oldRoot,newRoot){
  if(typeof value==='string'&&(key(value)===key(oldRoot)||inside(oldRoot,value))&&path.isAbsolute(value))return path.join(newRoot,path.relative(oldRoot,value))
  if(Array.isArray(value))return value.map(item=>rebase(item,oldRoot,newRoot))
  if(value&&typeof value==='object')return Object.fromEntries(Object.entries(value).map(([k,v])=>[path.isAbsolute(k)?rebase(k,oldRoot,newRoot):k,rebase(v,oldRoot,newRoot)]))
  return value
}
async function write(file,value){
  const temporary=file+'.'+crypto.randomUUID()+'.tmp'
  try{await fsp.writeFile(temporary,JSON.stringify(value,null,2));await fsp.rename(temporary,file)}finally{await fsp.unlink(temporary).catch(()=>{})}
}
async function directory(folder){
  const stat=await fsp.lstat(folder)
  if(!stat.isDirectory()||stat.isSymbolicLink()||key(await fsp.realpath(folder))!==key(folder))throw new Error('数据目录必须是普通文件夹：'+folder)
}
async function move(source,target,oldRoot,newRoot){
  if(!inside(oldRoot,source)||!inside(newRoot,target))throw new Error('迁移路径超出数据目录')
  try{await fsp.rename(source,target)}catch(error){
    if(error.code!=='EXDEV')throw error
    const temporary=target+'.migrating-'+crypto.randomUUID()
    try{
      await fsp.cp(source,temporary,{recursive:true,force:false,errorOnExist:true,dereference:false,verbatimSymlinks:true,preserveTimestamps:true})
      await fsp.rename(temporary,target)
      // Only remove the old item after the complete copy has been renamed into place.
      await fsp.rm(source,{recursive:true,force:false})
    }catch(copyError){await fsp.rm(temporary,{recursive:true,force:true}).catch(()=>{});throw copyError}
  }
}
async function prepare(legacy,destination){
  const oldRoot=path.resolve(legacy),newRoot=path.resolve(destination)
  if(key(oldRoot)===key(newRoot))return {migrated:false}
  if(inside(oldRoot,newRoot)||inside(newRoot,oldRoot)||newRoot===path.parse(newRoot).root)throw new Error('新旧数据目录不能互相包含')
  await fsp.mkdir(newRoot,{recursive:true});await directory(newRoot)
  if(fs.existsSync(path.join(newRoot,marker)))return {migrated:false}
  const migration=path.join(newRoot,journal)
  if(!fs.existsSync(migration)&&owned(newRoot)){
    await write(path.join(newRoot,marker),{version:1});return {migrated:false}
  }
  const exists=fs.existsSync(oldRoot)
  if(exists)await directory(oldRoot)
  if(!fs.existsSync(migration)&&(!exists||!owned(oldRoot))){
    await write(path.join(newRoot,marker),{version:1});return {migrated:false}
  }
  if(fs.existsSync(migration)){
    const saved=JSON.parse(await fsp.readFile(migration,'utf8'))
    if(key(saved.legacy)!==key(oldRoot))throw new Error('上次迁移来自不同目录，请保留数据并检查安装位置')
  }else await write(migration,{legacy:oldRoot})
  if(exists){
    for(const entry of await fsp.readdir(oldRoot,{withFileTypes:true})){
      if(entry.name===marker||entry.name===journal)throw new Error('旧目录包含未知迁移标记，未覆盖现有数据')
      const source=path.join(oldRoot,entry.name),target=path.join(newRoot,entry.name)
      if(fs.existsSync(target))throw new Error('迁移目标已存在，请勿删除任何文件：'+target)
      await move(source,target,oldRoot,newRoot)
    }
  }
  // Rewrite only paths inside the old app data. User-selected external paths stay intact.
  for(const relative of ['environment.json','last-state.json','jobs/audio-metadata.json']){
    const file=path.join(newRoot,relative)
    if(fs.existsSync(file)){
      let data
      try{data=JSON.parse(await fsp.readFile(file,'utf8'))}catch(error){if(error instanceof SyntaxError)continue;throw error}
      await write(file,rebase(data,oldRoot,newRoot))
    }
  }
  if(exists)await fsp.rmdir(oldRoot) // Empty-only removal; never erase remaining unknown files.
  await write(path.join(newRoot,marker),{version:1,migratedAt:new Date().toISOString()})
  await fsp.unlink(migration)
  return {migrated:true}
}
module.exports={location,prepare,rebase}
