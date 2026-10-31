const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs/promises'),path=require('node:path'),os=require('node:os')
const store=require('./environment-store.cjs')
test('delete only marked component folders; preserve tasks and sources',async()=>{
  const base=await fs.mkdtemp(path.join(os.tmpdir(),'tingye-environment-test-'))
  try{
    const data=path.join(base,'app'),runtime=path.join(data,'engine'),models=path.join(data,'models'),jobs=path.join(data,'jobs')
    await store.mark(runtime,'runtime');await store.mark(models,'models');await fs.mkdir(jobs)
    await fs.writeFile(path.join(jobs,'result.tysync'),'keep')
    const entries=await store.roots({},data)
    assert.equal(entries.length,2);await store.remove(entries,[data])
    assert.equal(await fs.readFile(path.join(jobs,'result.tysync'),'utf8'),'keep')
    assert.equal(await store.owned(runtime,'runtime'),false)
    assert.equal(await store.owned(data,'runtime',[data]),false)
  }finally{await fs.rm(base,{recursive:true,force:true})}
})
test('unmarked and parent folders cannot be deleted',async()=>{
  const base=await fs.mkdtemp(path.join(os.tmpdir(),'tingye-environment-test-'))
  try{
    await fs.writeFile(path.join(base,'source.epub'),'keep')
    await assert.rejects(store.remove([{root:base,kind:'runtime'}]))
    await store.mark(base,'runtime')
    await assert.rejects(store.remove([{root:base,kind:'runtime'}],[path.join(base,'jobs')]))
    assert.equal(await fs.readFile(path.join(base,'source.epub'),'utf8'),'keep')
  }finally{await fs.rm(base,{recursive:true,force:true})}
})
