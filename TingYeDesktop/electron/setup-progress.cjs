// Only a real package's byte count is shown; no invented overall percentage.
function setupProgress(event, previous='') {
  if (typeof event.detail==='string') {
    const line=event.detail.trim().slice(0,600)
    const bytes=/^Progress (\d+) of (\d+|None)$/.exec(line)
    if(bytes){
      const done=Number(bytes[1])/1048576,total=bytes[2]==='None'?0:Number(bytes[2])/1048576
      const label=previous.split(' · 已下载 ')[0]
      return `${label||'下载组件'} · 已下载 ${done.toFixed(1)} MB${total>0?` / ${total.toFixed(1)} MB`:''}`
    }
    if(line.startsWith('Collecting '))return `解析依赖：${line.slice(11).split(' (from')[0]}`
    if(line.startsWith('Requirement already satisfied:'))return `复用已安装组件：${line.slice(30).trim().split(' in ')[0]}`
    if(line.startsWith('Downloading '))return `下载：${line.slice(12)}`
    if(line.startsWith('Using cached '))return `复用已下载文件：${line.slice(13)}`
    if(line.startsWith('Installing collected packages:'))return '下载完成，正在安装依赖文件…'
    if(line.startsWith('Successfully installed '))return '语音依赖安装完成，正在校验…'
    if(/Retrying|resum/i.test(line))return `网络重试：${line}`
    return line
  }
  if(event.waitingSeconds>=15)return `${previous.split(' · 当前步骤')[0]||'正在处理组件'} · 当前步骤 ${event.waitingSeconds} 秒未返回新信息，网络超时将自动重试`
  return previous
}
module.exports={setupProgress}
