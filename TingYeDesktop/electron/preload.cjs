const {contextBridge,ipcRenderer}=require('electron')
const invoke=name=>(...args)=>ipcRenderer.invoke('tingye:'+name,...args)
contextBridge.exposeInMainWorld('tingye',Object.freeze({
  choose:invoke('choose'),environment:invoke('environment'),prepare:invoke('prepare'),
  removeEnvironment:invoke('removeEnvironment'),
  setMemoryBudget:invoke('setMemoryBudget'),
  environmentPaths:invoke('environmentPaths'),chooseEnvironmentPath:invoke('chooseEnvironmentPath'),
  start:invoke('start'),pause:invoke('pause'),export:invoke('export'),reveal:invoke('reveal'),
  state:invoke('state'),media:invoke('media'),
  history:invoke('history'),exportHistory:invoke('exportHistory'),removeHistory:invoke('removeHistory'),revealHistory:invoke('revealHistory'),
  window:action=>ipcRenderer.send('tingye:window',action),
  subscribe:callback=>{const listener=(_,value)=>callback(value);ipcRenderer.on('tingye:state',listener);return()=>ipcRenderer.removeListener('tingye:state',listener)}
}))
