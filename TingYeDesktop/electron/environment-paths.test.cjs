const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs/promises'),path=require('node:path'),os=require('node:os')
const paths=require('./environment-paths.cjs'),store=require('./environment-store.cjs')
async function fixture(run){
 const base=await fs.mkdtemp(path.join(os.tmpdir(),'tingye-paths-test-')),data=path.join(base,'app')
 await fs.mkdir(data)
 try{await run({base,data,context:{data,protectedPaths:[path.join(data,'jobs')]}})}
 finally{assert(path.resolve(base).startsWith(path.resolve(os.tmpdir())+path.sep+'tingye-paths-test-'));await fs.rm(base,{recursive:true,force:true})}
}
test('fresh computers use their own profile; path changes persist without changing other preferences',()=>fixture(async({base,data,context})=>{
 assert.deepEqual(await paths.load(data),paths.defaults(data))
 await fs.writeFile(path.join(data,'preferences.json'),JSON.stringify({memoryBudgetGiB:7}))
 const custom={runtimeDir:path.join(base,'自定义 组件'),modelsDir:path.join(base,'自定义 模型')}
 await paths.configure(data,custom,context)
 assert.deepEqual(await paths.load(data),custom)
 assert.deepEqual(JSON.parse(await fs.readFile(path.join(data,'preferences.json'),'utf8')),{memoryBudgetGiB:7})
 assert.equal(paths.pythonAt(custom.runtimeDir),null,'Never fall back to an old/default Python after choosing a new directory')
}))
test('import owned legacy paths once without copying or deleting their files',()=>fixture(async({base,data})=>{
 const runtime=path.join(base,'legacy-runtime'),models=path.join(base,'legacy-models'),python=path.join(runtime,'Scripts','python.exe')
 await store.mark(runtime,'runtime');await store.mark(models,'models');await fs.mkdir(path.dirname(python));await fs.writeFile(python,'legacy')
 const expected={runtimeDir:runtime,modelsDir:models}
 assert.deepEqual(await paths.load(data,{managedRuntime:runtime,python,models}),expected)
 assert.deepEqual(await paths.load(data,{}),expected)
 assert.equal(paths.pythonAt(runtime),python);assert.equal(await fs.readFile(python,'utf8'),'legacy')
}))
test('invalid choices preserve the saved configuration and unrelated files',()=>fixture(async({base,data,context})=>{
 const original=paths.defaults(data);await paths.configure(data,original,context)
 const unrelated=path.join(base,'novels');await fs.mkdir(unrelated);await fs.writeFile(path.join(unrelated,'book.epub'),'keep')
 for(const runtimeDir of [unrelated,data,path.join(data,'jobs'),path.parse(base).root,original.modelsDir,path.join(original.modelsDir,'nested')]){
  await assert.rejects(paths.configure(data,{...original,runtimeDir},context))
  assert.deepEqual(await paths.load(data),original)
 }
 assert.equal(await fs.readFile(path.join(unrelated,'book.epub'),'utf8'),'keep')
}))
test('reject model/runtime nesting and using the wrong kind of component folder',()=>fixture(async({base,data,context})=>{
 const runtime=path.join(base,'runtime');await store.mark(runtime,'runtime')
 await assert.rejects(paths.configure(data,{runtimeDir:runtime,modelsDir:path.join(runtime,'models')},context))
 await assert.rejects(paths.configure(data,{runtimeDir:path.join(base,'new-runtime'),modelsDir:runtime},context))
}))
test('reject links and descendants of links before claiming ownership',()=>fixture(async({base,data,context})=>{
 const actual=path.join(base,'actual'),link=path.join(base,'linked');await fs.mkdir(actual)
 await fs.symlink(actual,link,process.platform==='win32'?'junction':'dir')
 await assert.rejects(paths.configure(data,{runtimeDir:path.join(link,'child'),modelsDir:path.join(base,'models')},context))
 assert.deepEqual(await fs.readdir(actual),[])
}))
test('delete only the selected environment, retaining previous paths and task files',()=>fixture(async({base,data,context})=>{
 const original=paths.defaults(data),current={runtimeDir:path.join(base,'custom-runtime'),modelsDir:path.join(base,'custom-models')}
 for(const [folder,kind] of [[original.runtimeDir,'runtime'],[original.modelsDir,'models'],[current.runtimeDir,'runtime'],[current.modelsDir,'models']])await store.mark(folder,kind)
 await fs.mkdir(path.join(data,'jobs'));await fs.writeFile(path.join(data,'jobs','result.tysync'),'keep')
 await paths.configure(data,current,context)
 const entries=await store.roots({managedRuntime:current.runtimeDir,models:current.modelsDir},data,[],false)
 assert.equal(entries.length,2);await store.remove(entries,[data])
 assert.equal(await store.owned(original.runtimeDir,'runtime'),true);assert.equal(await store.owned(original.modelsDir,'models'),true)
 assert.equal(await fs.readFile(path.join(data,'jobs','result.tysync'),'utf8'),'keep')
}))
test('model readiness requires actual model files in the selected directory',()=>fixture(async({base})=>{
 assert.equal(paths.modelReady(base),false)
 const snapshot=path.join(base,'models','iic--SenseVoiceSmall','snapshots','master');await fs.mkdir(snapshot,{recursive:true})
 await fs.writeFile(path.join(snapshot,'model.pt'),'model');assert.equal(paths.modelReady(base),false)
 await fs.writeFile(path.join(snapshot,'config.yaml'),'config');assert.equal(paths.modelReady(base),true)
}))
