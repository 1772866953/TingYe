const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),fsp=fs.promises,path=require('node:path'),os=require('node:os'),{spawnSync}=require('node:child_process')
const history=require('./history-store.cjs'),A='a'.repeat(32),B='b'.repeat(32),C='c'.repeat(32)
async function fixture(run){
 const base=await fsp.mkdtemp(path.join(os.tmpdir(),'tingye-history-')),root=path.join(base,'jobs')
 await fsp.mkdir(root)
 const manifests=[{id:A,title:'测试小说甲',complete:true,count:42,compression:8},{id:B,title:'测试小说乙',complete:false,count:3,compression:0}]
 const python=process.env.TINGYE_TEST_PYTHON||'python'
 const generated=spawnSync(python,['-c',`import json,sys,zipfile,pathlib
root=pathlib.Path(sys.argv[1])
for item in json.loads(sys.argv[2]):
 folder=root/item['id'];folder.mkdir()
 manifest=dict(format='tingye-sentence-sync',version=1,book=dict(title=item['title']),complete=item['complete'],sentence_count=item['count'],coverage=.5,tracks=[dict(name='第一集.m4a',duration_ms=120000)])
 with zipfile.ZipFile(folder/'result.tysync','w',compression=item['compression']) as z:
  z.writestr('sentences.jsonl','{"text":"示例正文"}\\n')
  z.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False))
  z.comment=b'real Python zipfile fixture'
 (folder/'alignment.sqlite3').write_bytes(b'database fixture')
`,root,JSON.stringify(manifests)],{encoding:'utf8',windowsHide:true})
 assert.equal(generated.status,0,generated.stderr||String(generated.error))
 await fsp.writeFile(path.join(root,'audio-metadata.json'),'{}')
 try{await run({base,root})}finally{assert(path.resolve(base).startsWith(path.resolve(os.tmpdir())+path.sep+'tingye-history-'));await fsp.rm(base,{recursive:true,force:true})}
}
test('discover existing unexported caches and read real Python deflated/stored manifests without a GPU',()=>fixture(async({root})=>{
 await fsp.mkdir(path.join(root,'d'.repeat(32)))
 await fsp.utimes(path.join(root,A,'result.tysync'),new Date(10000),new Date(10000))
 const rows=await history.list(root)
 assert.deepEqual(rows.map(row=>row.id),[B,A]);assert.equal(rows[1].title,'测试小说甲');assert.equal(rows[1].matched,42)
 assert.equal(rows[0].complete,false);assert.equal(rows[1].durationMs,120000);assert.equal(rows[1].audioCount,1)
}))
test('re-export uses the exact cached bytes, works without source files, and refuses to overwrite the cache',()=>fixture(async({root,base})=>{
 const exported=path.join(base,'再次导出.tysync');await history.copy(root,A,exported)
 assert((await fsp.readFile(exported)).equals(await fsp.readFile(path.join(root,A,'result.tysync'))))
 await assert.rejects(history.copy(root,A,path.join(root,B,'result.tysync')))
 const alias=path.join(base,'linked-jobs');await fsp.symlink(root,alias,'junction')
 await assert.rejects(history.copy(root,A,path.join(alias,B,'result.tysync')))
}))
test('single and batch deletion remove selected databases and caches while preserving exports and other tasks',()=>fixture(async({root,base})=>{
 const external=path.join(base,'已导出.tysync');await history.copy(root,A,external)
 let outcome=await history.remove(root,[A]);assert.deepEqual(outcome.deleted,[A]);assert(fs.existsSync(path.join(root,B,'alignment.sqlite3')))
 await fsp.mkdir(path.join(root,C));await fsp.writeFile(path.join(root,C,'result.tysync'),'broken')
 outcome=await history.remove(root,[B,C]);assert.deepEqual(outcome.deleted,[B,C]);assert(fs.existsSync(external));assert(fs.existsSync(path.join(root,'audio-metadata.json')))
 assert.deepEqual(await history.list(root),[])
}))
test('invalid batch IDs and junctions are rejected before any valid record is deleted',()=>fixture(async({root,base})=>{
 for(const invalid of ['../other','..',path.join(base,'outside'),'a'.repeat(33)])await assert.rejects(history.remove(root,[A,invalid]))
 const outside=path.join(base,'outside');await fsp.mkdir(outside);await fsp.writeFile(path.join(outside,'keep'),'keep')
 await fsp.symlink(outside,path.join(root,C),'junction');await assert.rejects(history.remove(root,[A,C]))
 assert(fs.existsSync(path.join(root,A,'result.tysync')));assert.equal(await fsp.readFile(path.join(outside,'keep'),'utf8'),'keep')
 await assert.rejects(history.remove(root,[A],[path.join(root,A,'source.epub')]))
}))
test('damaged caches remain removable and do not hide healthy history',()=>fixture(async({root})=>{
 await fsp.mkdir(path.join(root,C));await fsp.writeFile(path.join(root,C,'result.tysync'),Buffer.alloc(0))
 const rows=await history.list(root);assert.equal(rows.length,3);assert(rows.find(row=>row.id===C).error)
 await assert.rejects(history.copy(root,C,path.join(root,'invalid-export.tysync')))
 await history.remove(root,[C]);assert.equal((await history.list(root)).length,2)
}))
