const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),fsp=fs.promises,path=require('node:path'),os=require('node:os')
const data=require('./app-data.cjs')
async function fixture(run){
 const base=await fsp.mkdtemp(path.join(os.tmpdir(),'tingye-migration-')),old=path.join(base,'旧用户目录'),target=path.join(base,'安装位置','data')
 await fsp.mkdir(old,{recursive:true})
 try{await run({base,old,target})}finally{assert(path.resolve(base).startsWith(path.resolve(os.tmpdir())+path.sep+'tingye-migration-'));await fsp.rm(base,{recursive:true,force:true})}
}
test('defaults follow the executable installation directory',()=>{
 const exe=path.join(os.tmpdir(),'another-user','Tingye','app.exe');assert.equal(data.location(exe),path.join(path.dirname(exe),'data'))
})
test('migrate complete app data, fix internal paths, preserve external paths and cached bytes',()=>fixture(async({old,target,base})=>{
 const job=path.join(old,'jobs','a'.repeat(32));await fsp.mkdir(job,{recursive:true});await fsp.mkdir(path.join(old,'engine'));await fsp.mkdir(path.join(old,'models'))
 const external=path.join(base,'小说.epub'),raw=Buffer.from('cached alignment and database')
 await fsp.writeFile(path.join(job,'result.tysync'),raw);await fsp.writeFile(path.join(job,'alignment.sqlite3'),raw)
 await fsp.writeFile(path.join(old,'environment.json'),JSON.stringify({runtimeDir:path.join(old,'engine'),modelsDir:path.join(old,'models')}))
 await fsp.writeFile(path.join(old,'preferences.json'),' {"memoryBudgetGiB":7}')
 await fsp.writeFile(path.join(old,'last-state.json'),JSON.stringify({result:path.join(job,'result.tysync'),sources:{novel:{path:external}},matched:379}))
 await fsp.writeFile(path.join(old,'jobs/audio-metadata.json'),JSON.stringify({[path.join(old,'audio.m4a')]:{track:{path:path.join(old,'audio.m4a')}}}))
 await fsp.writeFile(path.join(old,'notes.txt'),'preserve unknown files')
 assert.equal((await data.prepare(old,target)).migrated,true);assert(!fs.existsSync(old))
 const config=JSON.parse(await fsp.readFile(path.join(target,'environment.json')));assert.equal(config.runtimeDir,path.join(target,'engine'))
 const state=JSON.parse(await fsp.readFile(path.join(target,'last-state.json')));assert.equal(state.result,path.join(target,'jobs','a'.repeat(32),'result.tysync'));assert.equal(state.sources.novel.path,external);assert.equal(state.matched,379)
 const metadata=JSON.parse(await fsp.readFile(path.join(target,'jobs/audio-metadata.json')));assert.equal(metadata[path.join(target,'audio.m4a')].track.path,path.join(target,'audio.m4a'))
 assert((await fsp.readFile(state.result)).equals(raw));assert.equal(await fsp.readFile(path.join(target,'notes.txt'),'utf8'),'preserve unknown files')
 assert.equal((await data.prepare(old,target)).migrated,false)
}))
test('fresh installation stays local, and an existing local library is never overwritten by legacy data',()=>fixture(async({old,target})=>{
 assert.equal((await data.prepare(old,target)).migrated,false)
 await fsp.writeFile(path.join(target,'last-state.json'),'local')
 await fsp.writeFile(path.join(old,'last-state.json'),'legacy')
 await data.prepare(old,target);assert.equal(await fsp.readFile(path.join(target,'last-state.json'),'utf8'),'local');assert.equal(await fsp.readFile(path.join(old,'last-state.json'),'utf8'),'legacy')
}))
test('interrupted same-volume migration resumes and rewrites paths once all files are moved',()=>fixture(async({old,target})=>{
 await fsp.mkdir(target,{recursive:true});await fsp.mkdir(path.join(target,'engine'))
 await fsp.writeFile(path.join(target,'.tingye-migration.json'),JSON.stringify({legacy:old}))
 await fsp.writeFile(path.join(old,'environment.json'),JSON.stringify({runtimeDir:path.join(old,'engine'),modelsDir:path.join(old,'models')}))
 await data.prepare(old,target);assert(!fs.existsSync(old));assert.equal(JSON.parse(await fsp.readFile(path.join(target,'environment.json'))).runtimeDir,path.join(target,'engine'))
}))
test('nested or linked destinations are rejected without moving source data',()=>fixture(async({old,target,base})=>{
 await fsp.writeFile(path.join(old,'last-state.json'),'{}')
 await assert.rejects(data.prepare(old,path.join(old,'nested')))
 const linked=path.join(base,'link');await fsp.mkdir(target,{recursive:true});await fsp.symlink(target,linked,'junction')
 await assert.rejects(data.prepare(old,linked));assert(fs.existsSync(path.join(old,'last-state.json')))
}))
test('cross-volume fallback completes the copy before removing originals',()=>fixture(async({old,target})=>{
 await fsp.mkdir(path.join(old,'jobs'));await fsp.writeFile(path.join(old,'jobs/result.tysync'),'important result')
 await fsp.writeFile(path.join(old,'last-state.json'),JSON.stringify({matched:99}))
 const rename=fsp.rename
 fsp.rename=async(from,to)=>{if(path.dirname(from)===old)throw Object.assign(new Error('cross volume'),{code:'EXDEV'});return rename(from,to)}
 try{await data.prepare(old,target)}finally{fsp.rename=rename}
 assert(!fs.existsSync(old));assert.equal(await fsp.readFile(path.join(target,'jobs/result.tysync'),'utf8'),'important result')
}))
