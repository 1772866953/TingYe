const fs=require('node:fs'),fsp=fs.promises,path=require('node:path'),{inflateRaw}=require('node:zlib'),{promisify}=require('node:util')
const inflate=promisify(inflateRaw),validId=/^[a-f0-9]{32}$/
const key=p=>path.resolve(p).toLowerCase(),inside=(parent,child)=>key(child).startsWith(key(parent)+path.sep)
async function regular(file,directory=false){
  const stat=await fsp.lstat(file)
  if(stat.isSymbolicLink()||key(await fsp.realpath(file))!==key(file)||(directory?!stat.isDirectory():!stat.isFile()))throw new Error('不支持链接或异常缓存路径')
  return stat
}
async function job(root,id){
  if(typeof id!=='string'||!validId.test(id))throw new Error('无效的历史记录')
  await regular(root,true)
  const folder=path.join(path.resolve(root),id)
  await regular(folder,true)
  return folder
}
async function readAt(file,length,position){
  const data=Buffer.alloc(length),{bytesRead}=await file.read(data,0,length,position)
  if(bytesRead!==length)throw new Error('对应文件不完整')
  return data
}
// Read only the small manifest entry. Listing never inflates the sentence index,
// needs Python/GPU, or follows paths stored inside the archive.
async function manifest(file){
  await regular(file)
  const handle=await fsp.open(file,'r')
  try{
    const stat=await handle.stat(),tail=await readAt(handle,Math.min(stat.size,65557),Math.max(0,stat.size-65557))
    let end=-1
    for(let i=tail.length-22;i>=0;i--)if(tail.readUInt32LE(i)===0x06054b50&&i+22+tail.readUInt16LE(i+20)===tail.length){end=i;break}
    if(end<0||tail.readUInt16LE(end+4)||tail.readUInt16LE(end+6))throw new Error('对应文件格式无效')
    const centralSize=tail.readUInt32LE(end+12),centralOffset=tail.readUInt32LE(end+16)
    if(centralSize>1048576||centralOffset+centralSize>stat.size)throw new Error('对应文件目录无效')
    const central=await readAt(handle,centralSize,centralOffset)
    for(let i=0;i+46<=central.length;){
      if(central.readUInt32LE(i)!==0x02014b50)throw new Error('对应文件目录损坏')
      const nameLength=central.readUInt16LE(i+28),extra=central.readUInt16LE(i+30),comment=central.readUInt16LE(i+32),next=i+46+nameLength+extra+comment
      if(next>central.length)throw new Error('对应文件目录不完整')
      const name=central.toString('utf8',i+46,i+46+nameLength)
      if(name==='manifest.json'){
        const flags=central.readUInt16LE(i+8),method=central.readUInt16LE(i+10),compressed=central.readUInt32LE(i+20),size=central.readUInt32LE(i+24),offset=central.readUInt32LE(i+42)
        if(flags&1||![0,8].includes(method)||size>8388608||compressed>8388608||offset+30>stat.size)throw new Error('对应文件清单无效')
        const header=await readAt(handle,30,offset)
        if(header.readUInt32LE(0)!==0x04034b50)throw new Error('对应文件头损坏')
        const start=offset+30+header.readUInt16LE(26)+header.readUInt16LE(28)
        if(start+compressed>centralOffset)throw new Error('对应文件清单不完整')
        const data=await readAt(handle,compressed,start),decoded=method===8?await inflate(data,{maxOutputLength:8388608}):data
        if(decoded.length!==size)throw new Error('对应文件清单长度不符')
        const value=JSON.parse(decoded.toString('utf8'))
        if(value.format!=='tingye-sentence-sync'||value.version!==1||!value.book||!Array.isArray(value.tracks)||!Number.isSafeInteger(value.sentence_count)||value.sentence_count<0)throw new Error('不是有效的听页逐句对应文件')
        return {value,stat}
      }
      i=next
    }
    throw new Error('对应文件缺少清单')
  }finally{await handle.close()}
}
async function entry(root,id){
  const folder=await job(root,id),file=path.join(folder,'result.tysync'),stat=await regular(file)
  const base={id,title:'无法读取的对应记录',updatedAt:stat.mtimeMs,createdAt:stat.birthtimeMs,size:stat.size,cachePath:file,complete:false,matched:0,coverage:0,audioCount:0,durationMs:0,tracks:[]}
  try{
    const {value,stat:snapshot}=await manifest(file)
    return {...base,title:String(value.book.title||'未命名小说'),updatedAt:snapshot.mtimeMs,size:snapshot.size,complete:value.complete===true,matched:value.sentence_count,coverage:Math.max(0,Math.min(1,Number(value.coverage)||0)),audioCount:value.tracks.length,durationMs:value.tracks.reduce((sum,t)=>sum+(Number(t.duration_ms)||0),0),tracks:value.tracks.map(t=>({name:String(t.name||'未命名音频'),durationMs:Number(t.duration_ms)||0}))}
  }catch(error){return {...base,error:'缓存文件无法读取，可删除此记录后重新识别'}}
}
async function list(root){
  if(!fs.existsSync(root))return []
  await regular(root,true)
  const entries=[]
  for(const item of await fsp.readdir(root,{withFileTypes:true}))if(item.isDirectory()&&!item.isSymbolicLink()&&validId.test(item.name)){
    try{const row=await entry(root,item.name);row.tracks=row.tracks.slice(0,5);entries.push(row)}catch{}
  }
  return entries.sort((a,b)=>b.updatedAt-a.updatedAt)
}
async function result(root,id){
  const record=await entry(root,id)
  if(record.error)throw new Error(record.error)
  return record
}
async function copy(root,id,destination){
  const record=await result(root,id)
  if(typeof destination!=='string'||!path.isAbsolute(destination)||inside(root,destination)||key(root)===key(destination))throw new Error('请将文件导出到任务缓存目录之外')
  // Also reject a destination parent that aliases the cache via a junction.
  const parent=await fsp.realpath(path.dirname(destination))
  if(inside(root,parent)||key(root)===key(parent))throw new Error('请将文件导出到任务缓存目录之外')
  if(fs.existsSync(destination)){const real=await fsp.realpath(destination);if(inside(root,real))throw new Error('不能覆盖历史缓存文件')}
  await fsp.copyFile(record.cachePath,destination)
  return destination
}
async function remove(root,ids,protectedPaths=[]){
  if(!Array.isArray(ids)||!ids.length||ids.length>10000||new Set(ids).size!==ids.length)throw new Error('请选择要删除的历史记录')
  const targets=[]
  // Validate the entire selection before changing the first record.
  for(const id of ids){
    const folder=await job(root,id)
    await regular(path.join(folder,'result.tysync'))
    if(protectedPaths.some(file=>key(file)===key(folder)||inside(folder,file)))throw new Error('此缓存目录包含当前选中的源文件，无法删除')
    targets.push({id,folder})
  }
  const deleted=[],failed=[]
  for(const {id,folder} of targets){
    try{await fsp.rm(folder,{recursive:true,force:false,maxRetries:2,retryDelay:100});deleted.push(id)}
    catch{failed.push({id,message:'文件正在使用或无法删除，请稍后重试'})}
  }
  return {deleted,failed}
}
module.exports={list,entry,result,copy,remove}
