import { createContext, forwardRef, useContext, useEffect, useId, useImperativeHandle, useMemo, useRef, useState, type ButtonHTMLAttributes, type InputHTMLAttributes, type PointerEvent, type ReactNode } from 'react'
import { motion, useMotionTemplate, useSpring } from 'motion/react'

export const AppearanceContext=createContext({dark:false,reduced:false})
const buttonDragStrength=.8
function GlassLens({width,height,radius}:{width:number;height:number;radius:number}){
  const id=useId().replaceAll(':','')
  const map=useMemo(()=>{
    const ratio=Math.min(1,720/width,90/height),w=Math.max(1,Math.round(width*ratio)),h=Math.max(1,Math.round(height*ratio)),r=Math.min(radius,height/2)*ratio
    const canvas=document.createElement('canvas');canvas.width=w;canvas.height=h
    const context=canvas.getContext('2d')!;const pixels=context.createImageData(w,h)
    for(let y=0;y<h;y++)for(let x=0;x<w;x++){
      const qx=Math.abs(x+.5-w/2)-(w/2-r),qy=Math.abs(y+.5-h/2)-(h/2-r)
      const outsideX=Math.max(qx,0),outsideY=Math.max(qy,0)
      const distance=-(Math.min(Math.max(qx,qy),0)+Math.hypot(outsideX,outsideY)-r)
      let nx=outsideX*Math.sign(x+.5-w/2),ny=outsideY*Math.sign(y+.5-h/2)
      const length=Math.hypot(nx,ny)
      if(length){nx/=length;ny/=length}else if(qx>qy){nx=Math.sign(x+.5-w/2)}else{ny=Math.sign(y+.5-h/2)}
      const strength=Math.max(0,1-Math.max(0,distance)/(7*ratio))**2
      const at=(y*w+x)*4;pixels.data[at]=128+nx*110*strength;pixels.data[at+1]=128+ny*110*strength;pixels.data[at+2]=128;pixels.data[at+3]=255
    }
    context.putImageData(pixels,0,0);return canvas.toDataURL()
  },[width,height,radius])
  return <><svg className="glass-filter-defs" aria-hidden="true"><defs><filter id={id} x="0" y="0" width="100%" height="100%" colorInterpolationFilters="sRGB">
    <feImage href={map} x="0" y="0" width="100%" height="100%" preserveAspectRatio="none" result="lens-map"/>
    <feDisplacementMap in="SourceGraphic" in2="lens-map" scale="10" xChannelSelector="R" yChannelSelector="G"/>
  </filter></defs></svg><span className="glass-refraction" style={{backdropFilter:`url(#${id}) blur(2px) saturate(1.1)`}}/></>
}

/** Glass and foreground share the same spring; optical filtering stays behind the text. */
function useLiquidMotion(disabled=false,thumb=false){
  const {reduced}=useContext(AppearanceContext)
  const state=useRef({hover:false,pressed:false,px:0,py:0,dx:0,dy:0,startX:0,startY:0,dragged:false,host:null as HTMLElement|null})
  const physics={stiffness:420,damping:22,mass:.65}
  const x=useSpring(0,physics),y=useSpring(0,physics)
  const scaleX=useSpring(1,physics),scaleY=useSpring(1,physics),shear=useSpring(0,physics)
  const transform=useMotionTemplate`matrix(${scaleX}, ${shear}, ${shear}, ${scaleY}, ${x}, ${y})`
  const lightX=useSpring(50,{stiffness:480,damping:32}),lightY=useSpring(30,{stiffness:480,damping:32})
  const light=useSpring(0,{stiffness:380,damping:28})
  const shadow=useSpring(0,{stiffness:380,damping:28})
  const boxShadow=useMotionTemplate`0 3px 8px rgb(46 75 59 / ${shadow}), inset 0 1px 1px #fff, inset 0 -1px 2px #ffffffa0`
  const background=useMotionTemplate`radial-gradient(ellipse at ${lightX}% ${lightY}%, #ffffffa8, #ffffff18 44%, transparent 72%)`
  function update(){
    const {hover,pressed,px,py,dx,dy}=state.current,active=!disabled&&!reduced&&(hover||pressed)
    let a=1,d=1,b=0,tx=0,ty=0
    if(active&&thumb){a=pressed?1.18:1.08;d=pressed?1.12:1.08}
    else if(active&&pressed){
      const distance=Math.hypot(dx,dy),amount=1-Math.exp(-distance/32)
      const ux=distance?dx/distance:0,uy=distance?dy/distance:0
      const along=.98+.19*amount,across=.98-.055*amount
      // Symmetric strain stretches along the drag vector without rotating the whole button.
      a=distance?across+(along-across)*ux*ux:.98
      d=distance?across+(along-across)*uy*uy:.98
      b=(along-across)*ux*uy
      tx=12*Math.tanh(dx/42);ty=10*Math.tanh(dy/36)
    }else if(active){a=1.018+Math.abs(px)*.012;d=1.045;tx=px*2.8;ty=py*2}
    if(active&&pressed&&!thumb){
      a=1+(a-1)*buttonDragStrength;d=1+(d-1)*buttonDragStrength;b*=buttonDragStrength
      tx*=buttonDragStrength;ty*=buttonDragStrength
    }
    x.set(tx);y.set(ty);scaleX.set(a);scaleY.set(d);shear.set(b)
    light.set(active?(pressed?.6:.38):0)
    shadow.set(active?.12:0)
  }
  function containsThumb(node:HTMLElement,clientX:number,clientY:number){
    const input=node as HTMLInputElement,box=node.getBoundingClientRect()
    const fraction=(Number(input.value)-Number(input.min))/(Number(input.max)-Number(input.min)||1)
    const centerX=box.left+16+(box.width-32)*fraction
    return Math.hypot(clientX-centerX,clientY-box.top-box.height/2)<=18
  }
  function point(event:PointerEvent<HTMLElement>){
    const current=state.current,box=event.currentTarget.getBoundingClientRect()
    if(current.pressed&&!thumb){
      current.dx=event.clientX-current.startX;current.dy=event.clientY-current.startY
      if(Math.hypot(current.dx,current.dy)>5)current.dragged=true
    }
    current.hover=event.pointerType!=='touch'&&(thumb?containsThumb(event.currentTarget,event.clientX,event.clientY):
      event.clientX>=box.left&&event.clientX<=box.right&&event.clientY>=box.top&&event.clientY<=box.bottom)
    state.current.px=Math.max(-1,Math.min(1,(event.clientX-box.left)/box.width*2-1))
    state.current.py=Math.max(-1,Math.min(1,(event.clientY-box.top)/box.height*2-1))
    lightX.set(50+state.current.px*40);lightY.set(50+state.current.py*40);update()
  }
  function release(event?:globalThis.PointerEvent){
    const current=state.current
    if(event&&current.host){
      const box=current.host.getBoundingClientRect()
      const inside=event.clientX>=box.left&&event.clientX<=box.right&&event.clientY>=box.top&&event.clientY<=box.bottom
      current.hover=event.pointerType!=='touch'&&inside&&(!thumb||containsThumb(current.host,event.clientX,event.clientY))
      if(!inside)current.dragged=true
    }
    current.pressed=false;current.dx=0;current.dy=0;update()
  }
  function cancel(){state.current.hover=false;state.current.dragged=true;release()}
  useEffect(()=>{
    const pointerUp=(event:globalThis.PointerEvent)=>{if(state.current.pressed)release(event)}
    const reset=()=>{if(state.current.pressed||state.current.hover)cancel()}
    window.addEventListener('pointerup',pointerUp);window.addEventListener('pointercancel',reset);window.addEventListener('blur',reset)
    return()=>{window.removeEventListener('pointerup',pointerUp);window.removeEventListener('pointercancel',reset);window.removeEventListener('blur',reset)}
  },[disabled,reduced])
  useEffect(()=>{update()},[disabled,reduced])
  return {style:{transform},thumbStyle:{transform,boxShadow},highlight:{background,opacity:light},
    enter:point,move:point,
    press:(event?:PointerEvent<HTMLElement>)=>{
      if(disabled||(event&&event.button!==0))return
      const current=state.current;current.dragged=false;current.dx=0;current.dy=0;current.pressed=true
      if(event){current.host=event.currentTarget;current.startX=event.clientX;current.startY=event.clientY
        if(!thumb)event.currentTarget.setPointerCapture(event.pointerId)
        point(event)
      }else{current.host=null;update()}
    },release,cancel,wasDragged:()=>state.current.dragged,
    lostCapture:()=>{if(state.current.pressed)cancel()},
    leave:()=>{state.current.hover=false;update()}}
}

export const GlassButton=forwardRef<HTMLButtonElement,ButtonHTMLAttributes<HTMLButtonElement>&{radius?:number}>(function GlassButton({children,className='',radius=999,style,...props},forwarded){
  const host=useRef<HTMLButtonElement>(null)
  useImperativeHandle(forwarded,()=>host.current!)
  const liquid=useLiquidMotion(props.disabled)
  const [size,setSize]=useState({width:0,height:0})
  useEffect(()=>{const node=host.current;if(!node)return;const observer=new ResizeObserver(()=>setSize({width:node.offsetWidth,height:node.offsetHeight}));observer.observe(node);return()=>observer.disconnect()},[])
  return <button {...props} ref={host} type={props.type||'button'} className={`glass-button ${className}`} style={{...style,borderRadius:radius}}
    onPointerEnter={event=>{liquid.enter(event);props.onPointerEnter?.(event)}}
    onPointerMove={event=>{liquid.move(event);props.onPointerMove?.(event)}}
    onPointerLeave={event=>{liquid.leave();props.onPointerLeave?.(event)}}
    onPointerDown={event=>{liquid.press(event);props.onPointerDown?.(event)}}
    onLostPointerCapture={event=>{liquid.lostCapture();props.onLostPointerCapture?.(event)}}
    onPointerCancel={event=>{liquid.cancel();props.onPointerCancel?.(event)}}
    onClickCapture={event=>{if(liquid.wasDragged()){event.preventDefault();event.stopPropagation()}else props.onClickCapture?.(event)}}
    onKeyDown={event=>{if(!event.repeat&&[' ','Enter'].includes(event.key))liquid.press();props.onKeyDown?.(event)}}
    onKeyUp={event=>{liquid.release();props.onKeyUp?.(event)}}
    onBlur={event=>{liquid.cancel();props.onBlur?.(event)}}>
    <motion.span className="glass-material" style={liquid.style} aria-hidden="true">
      {size.width>0&&<GlassLens width={size.width} height={size.height} radius={radius}/>}
      <span className="glass-rim"/><span className="glass-sheen"/><motion.span className="glass-highlight" style={liquid.highlight}/>
    </motion.span>
    <motion.span className="glass-content" style={liquid.style}>{children}</motion.span>
  </button>
})

/** Native range handles pointer/keyboard input; its glass thumb follows value without a position spring. */
export function GlassSlider({className='',value,min=0,max=100,disabled,...props}:InputHTMLAttributes<HTMLInputElement>){
  const liquid=useLiquidMotion(disabled,true)
  const fraction=Math.max(0,Math.min(1,(Number(value)-Number(min))/(Number(max)-Number(min)||1)))
  return <div className={`glass-slider ${className}`}>
    <div className="glass-slider-track" aria-hidden="true"><span style={{width:`${fraction*100}%`}}/></div>
    <div className="glass-slider-travel" aria-hidden="true"><span className="glass-thumb" style={{left:`${fraction*100}%`}}>
      <motion.span className="glass-material" style={liquid.thumbStyle}><GlassLens width={32} height={32} radius={16}/><span className="glass-rim"/><span className="glass-sheen"/><motion.span className="glass-highlight" style={liquid.highlight}/></motion.span>
    </span></div>
    <input {...props} type="range" value={value} min={min} max={max} disabled={disabled}
      onPointerEnter={event=>{liquid.enter(event);props.onPointerEnter?.(event)}}
      onPointerMove={event=>{liquid.move(event);props.onPointerMove?.(event)}}
      onPointerLeave={event=>{liquid.leave();props.onPointerLeave?.(event)}}
      onPointerDown={event=>{liquid.press(event);props.onPointerDown?.(event)}}
      onPointerUp={event=>{liquid.release(event.nativeEvent);props.onPointerUp?.(event)}}
      onPointerCancel={event=>{liquid.cancel();props.onPointerCancel?.(event)}}
      onKeyDown={event=>{if(['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Home','End','PageUp','PageDown'].includes(event.key))liquid.press();props.onKeyDown?.(event)}}
      onKeyUp={event=>{liquid.release();props.onKeyUp?.(event)}}
      onBlur={event=>{liquid.cancel();props.onBlur?.(event)}}/>
  </div>
}

export function GlassSurface({children,className='',radius=26}: {children:ReactNode;className?:string;radius?:number;kind?:string;interactive?:boolean}){
  return <div className={`glass-panel ${className}`} style={{borderRadius:radius}}><div className="glass-content">{children}</div></div>
}
