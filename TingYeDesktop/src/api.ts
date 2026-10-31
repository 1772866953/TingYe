export type Source = { path: string; name: string; size: number }
export type Preview = { n0: number; n1: number; audio: string; t0: number; t1: number; confidence: number; text: string }
export type JobState = {
  status: 'idle' | 'preparing' | 'running' | 'pausing' | 'paused' | 'completed' | 'error'
  message: string; progress: number; completed: number; total: number; matched: number
  processedSeconds: number; totalSeconds: number; track: string; preview: Preview[]
  result?: string; coverage?: number; error?: string; device?: string
  batchSize?: number; speed?: number | null; vramGiB?: number
  setupDetail?: string
  phase?: 'recognition'|'verification'
  verificationDone?:number; verificationTotal?:number; verificationAdded?:number
  sources?: {novel: Source | null; audio: Source[]}
}
export type Environment = { ready: boolean; device: string; message: string; removable?: boolean; memoryBudgetGiB?: number }
export type EnvironmentPaths = { runtimeDir:string; modelsDir:string }
export type HistoryRecord = {
  id:string; title:string; updatedAt:number; createdAt:number; size:number; cachePath:string
  complete:boolean; matched:number; coverage:number; audioCount:number; durationMs:number
  tracks:{name:string;durationMs:number}[]; error?:string
}
export type Bridge = {
  choose: (kind: 'novel' | 'audio' | 'folder') => Promise<Source[]>
  environment: () => Promise<Environment>
  prepare: () => Promise<void>
  removeEnvironment: () => Promise<boolean>
  setMemoryBudget: (value:number) => Promise<void>
  environmentPaths: () => Promise<EnvironmentPaths>
  chooseEnvironmentPath: (kind:'runtime'|'models'|'default') => Promise<EnvironmentPaths|null>
  start: (novel: string, audio: string[]) => Promise<void>
  pause: () => Promise<void>
  export: () => Promise<string | null>
  reveal: () => Promise<void>
  history: () => Promise<HistoryRecord[]>
  exportHistory: (id:string) => Promise<string|null>
  removeHistory: (ids:string[]) => Promise<{deleted:string[];failed:{id:string;message:string}[]}>
  revealHistory: (id:string) => Promise<void>
  state: () => Promise<JobState>
  media: (id: string) => Promise<string>
  window: (action: 'minimize' | 'maximize' | 'close') => void
  subscribe: (listener: (state: JobState) => void) => () => void
}
declare global { interface Window { tingye: Bridge } }
