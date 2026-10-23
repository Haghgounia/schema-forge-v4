import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent,
  type PointerEvent as ReactPointerEvent } from 'react';
import { renderDiagramSvg, type DiagramEngine } from '../diagramRender';
import { canvasBounds, diagramEntities, fitDiagram, focusAt, svgBounds, svgEntityPoint, zoomAt,
  type FitMode, type Point, type View } from '../diagramViewport';
import { saveDownload } from '../transport';
import { buildFocusView, readFocusGraph, type DiagramFocusView } from '../diagramFocus';

const focusLabels = {
  activate: '\u0646\u0645\u0627\u06cc\u0634 \u0631\u0648\u0627\u0628\u0637',
  full: '\u0628\u0627\u0632\u06af\u0634\u062a \u0628\u0647 \u0646\u0645\u0627\u06cc \u06a9\u0627\u0645\u0644',
  depth: '\u0639\u0645\u0642 \u0627\u0631\u062a\u0628\u0627\u0637\u0627\u062a',
  direct: '\u0631\u0648\u0627\u0628\u0637 \u0645\u0633\u062a\u0642\u06cc\u0645',
  two: '\u062f\u0648 \u0633\u0637\u062d \u0631\u0648\u0627\u0628\u0637',
  choose: '\u0627\u0628\u062a\u062f\u0627 \u0646\u0627\u0645 \u062c\u062f\u0648\u0644 \u0631\u0627 \u062f\u0631 \u06a9\u0627\u062f\u0631 \u062c\u0633\u062a\u200c\u0648\u062c\u0648 \u0648\u0627\u0631\u062f \u06a9\u0646\u06cc\u062f.',
  missing: '\u062c\u062f\u0648\u0644 \u0645\u0648\u0631\u062f\u0646\u0638\u0631 \u062f\u0631 \u0646\u0645\u0648\u062f\u0627\u0631 \u06cc\u0627\u0641\u062a \u0646\u0634\u062f.',
  large: '\u062a\u0639\u062f\u0627\u062f \u062c\u062f\u0648\u0644\u200c\u0647\u0627 \u06cc\u0627 \u0631\u0648\u0627\u0628\u0637 \u0627\u06cc\u0646 \u0646\u0645\u0627 \u0632\u06cc\u0627\u062f \u0627\u0633\u062a\u061b \u0639\u0645\u0642 \u06f1 \u0631\u0627 \u0627\u0646\u062a\u062e\u0627\u0628 \u06a9\u0646\u06cc\u062f.',
  unsupported: '\u0628\u0631\u0627\u06cc \u0627\u06cc\u0646 \u0646\u0648\u0639 \u0641\u0627\u06cc\u0644\u060c Focus Mode \u062f\u0631 \u062f\u0633\u062a\u0631\u0633 \u0646\u06cc\u0633\u062a.',
  outside: '\u0627\u06cc\u0646 \u062c\u062f\u0648\u0644 \u062e\u0627\u0631\u062c \u0627\u0632 \u0646\u0645\u0627\u06cc \u0645\u062a\u0645\u0631\u06a9\u0632 \u0627\u0633\u062a\u061b \u0628\u0631\u0627\u06cc \u062a\u063a\u06cc\u06cc\u0631 \u062c\u062f\u0648\u0644 \u0645\u0631\u06a9\u0632\u06cc\u060c \u062f\u0648\u0628\u0627\u0631\u0647 \u0646\u0645\u0627\u06cc\u0634 \u0631\u0648\u0627\u0628\u0637 \u0631\u0627 \u0628\u0632\u0646\u06cc\u062f.',
  summary: '\u0646\u0645\u0627\u06cc \u0645\u062a\u0645\u0631\u06a9\u0632',
  nodes: '\u062c\u062f\u0648\u0644',
  edges: '\u0631\u0627\u0628\u0637\u0647',
  safe: '\u0641\u0642\u0637 \u0646\u0645\u0627\u06cc \u0646\u0645\u0648\u062f\u0627\u0631 \u0645\u062d\u062f\u0648\u062f \u0634\u062f\u0647 \u0627\u0633\u062a\u061b ZIP \u0648 \u06af\u0632\u0627\u0631\u0634 \u0627\u0635\u0644\u06cc \u062a\u063a\u06cc\u06cc\u0631\u06cc \u0646\u06a9\u0631\u062f\u0647\u200c\u0627\u0646\u062f.',
  failed: '\u062a\u0631\u0633\u06cc\u0645 \u0632\u06cc\u0631\u0646\u0645\u0648\u062f\u0627\u0631 \u0645\u0645\u06a9\u0646 \u0646\u0628\u0648\u062f\u061b \u0646\u0645\u0627\u06cc \u0627\u0635\u0644\u06cc \u0628\u0627\u0632\u06af\u0631\u062f\u0627\u0646\u062f\u0647 \u0634\u062f.',
};

const START_VIEW: View = {x:0,y:0,scale:1};
type Drag = {id:number;last:Point};

/** Used by both Generation ZIP and the standalone Diagram screen. SVG never enters the HTML DOM. */
export default function RenderedDiagram({engine, source, filename}: {
  engine: DiagramEngine; source: string; filename: string;
}) {
  const [url, setUrl] = useState('');
  const [svg, setSvg] = useState('');
  const [view, setView] = useState<View>(START_VIEW);
  const [viewMode, setViewMode] = useState<FitMode | 'custom'>('screen');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [dragging, setDragging] = useState(false);
  const [entityQuery, setEntityQuery] = useState('');
  const [entityStatus, setEntityStatus] = useState('');
  const [markedEntity, setMarkedEntity] = useState<Point | null>(null);
  const [fallbackExpanded, setFallbackExpanded] = useState(false);
  const [focus, setFocus] = useState<DiagramFocusView | null>(null);
  const [focusDepth, setFocusDepth] = useState<1 | 2>(1);
  const [focusNotice, setFocusNotice] = useState('');
  const [nativeFullscreen, setNativeFullscreen] = useState(false);
  const viewportRef = useRef<HTMLDivElement>(null);
  const rootRef = useRef<HTMLDivElement>(null);
  const dragRef = useRef<Drag | null>(null);
  const viewRef = useRef<View>(START_VIEW);
  const preferredMode = useRef<FitMode | 'custom'>('screen');
  const datalistId = useId();
  const entities = useMemo(() => diagramEntities(engine,source),[engine,source]);
  const focusGraph = useMemo(() => readFocusGraph(engine,source),[engine,source]);
  const visibleSource = focus?.source ?? source;
  const suggestions = useMemo(() => {
    const query=entityQuery.trim().toUpperCase();
    return entities.filter(entity=>!query || entity.label.toUpperCase().includes(query)).slice(0,60);
  },[entities,entityQuery]);
  const bounds = useMemo(() => svgBounds(svg),[svg]);
  const canvas = useMemo(() => bounds ? canvasBounds(bounds) : null,[bounds]);

  function commit(next: View) { viewRef.current=next; setView(next); }
  function custom() { if(preferredMode.current!=='custom') {preferredMode.current='custom';setViewMode('custom');} }
  function viewportSize():Point {
    return {x:viewportRef.current?.clientWidth ?? 0,y:viewportRef.current?.clientHeight ?? 0};
  }
  function fit(mode:FitMode) {
    if(!canvas || !viewportRef.current) return;
    preferredMode.current=mode; setViewMode(mode);
    commit(fitDiagram(viewportSize(),canvas,mode));
  }
  function zoom(multiplier:number, anchor?:Point) {
    const size=viewportSize();
    if(!size.x || !size.y || !url) return;
    custom();
    commit(zoomAt(viewRef.current,multiplier,anchor ?? {x:size.x/2,y:size.y/2}));
  }

  useEffect(() => {
    setFocus(null);setFocusDepth(1);setFocusNotice('');setEntityQuery('');setEntityStatus('');
  },[engine,source,filename]);

  useEffect(() => {
    let alive=true;
    let blobUrl:string|undefined;
    setUrl(''); setSvg(''); setError(''); setBusy(true);
    setEntityStatus(''); setMarkedEntity(null);
    preferredMode.current='screen'; setViewMode('screen');
    viewRef.current=START_VIEW; setView(START_VIEW);
    renderDiagramSvg(engine,visibleSource).then(value => {
      if (!alive) return;
      const size=svgBounds(value);
      if(!size) throw new Error('ابعاد SVG از خروجی نمودار قابل تشخیص نیست.');
      const point=focus ? svgEntityPoint(value,engine,focus.root) : null;
      const area=canvasBounds(size);
      if(point) setMarkedEntity({x:point.x*area.width/size.width,y:point.y*area.height/size.height});
      blobUrl=URL.createObjectURL(new Blob([value],{type:'image/svg+xml;charset=utf-8'}));
      setSvg(value);setUrl(blobUrl);
    }).catch(reason => {
      if(!alive) return;
      if(focus) {setFocus(null);setFocusNotice(focusLabels.failed);}
      else setError(reason instanceof Error ? reason.message : 'نمایش نمودار ناموفق بود.');
    }).finally(() => {if(alive) setBusy(false);});
    return () => {alive=false;if(blobUrl) URL.revokeObjectURL(blobUrl);};
  },[engine,visibleSource,focus?.root.id]);

  useEffect(() => {
    const element=viewportRef.current;
    if(!element || !url || !canvas) return;
    const refresh=() => {
      const mode=preferredMode.current;
      if(mode !== 'custom') commit(fitDiagram(viewportSize(),canvas,mode));
    };
    refresh();
    if(typeof ResizeObserver==='undefined') return;
    const observer=new ResizeObserver(refresh);
    observer.observe(element);
    return () => observer.disconnect();
  },[url,canvas]);

  useEffect(() => {
    const element=viewportRef.current;
    if(!element || !url) return;
    const wheel=(event:WheelEvent) => {
      event.preventDefault();
      const rect=element.getBoundingClientRect();
      const delta=Math.max(-400,Math.min(400,event.deltaY));
      custom();
      commit(zoomAt(viewRef.current,Math.exp(-delta*.002),{
        x:event.clientX-rect.left,y:event.clientY-rect.top,
      }));
    };
    element.addEventListener('wheel',wheel,{passive:false});
    return () => element.removeEventListener('wheel',wheel);
  },[url]);

  useEffect(() => {
    const changed=() => setNativeFullscreen(document.fullscreenElement===rootRef.current);
    const escape=(event:KeyboardEvent) => {if(event.key==='Escape') setFallbackExpanded(false);};
    document.addEventListener('fullscreenchange',changed);
    document.addEventListener('keydown',escape);
    return () => {document.removeEventListener('fullscreenchange',changed);document.removeEventListener('keydown',escape);};
  },[]);

  function pointerStart(event:ReactPointerEvent<HTMLDivElement>) {
    if(!url || event.button!==0) return;
    dragRef.current={id:event.pointerId,last:{x:event.clientX,y:event.clientY}};
    event.currentTarget.setPointerCapture(event.pointerId);
    setDragging(true);
  }
  function pointerMove(event:ReactPointerEvent<HTMLDivElement>) {
    const drag=dragRef.current;
    if(!drag || drag.id!==event.pointerId) return;
    commit({...viewRef.current,
      x:viewRef.current.x+event.clientX-drag.last.x,
      y:viewRef.current.y+event.clientY-drag.last.y});
    drag.last={x:event.clientX,y:event.clientY};
    custom();
  }
  function pointerEnd(event:ReactPointerEvent<HTMLDivElement>) {
    if(dragRef.current?.id !== event.pointerId) return;
    dragRef.current=null;setDragging(false);
    if(event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
  }
  function keyboard(event:ReactKeyboardEvent<HTMLDivElement>) {
    const moves:Record<string,Point>={ArrowUp:{x:0,y:60},ArrowDown:{x:0,y:-60},
      ArrowLeft:{x:60,y:0},ArrowRight:{x:-60,y:0}};
    if(event.key in moves) {
      event.preventDefault();const delta=moves[event.key];custom();
      commit({...viewRef.current,x:viewRef.current.x+delta.x,y:viewRef.current.y+delta.y});
    } else if(['+','=','Add'].includes(event.key)) {event.preventDefault();zoom(1.3);}
    else if(['-','Subtract'].includes(event.key)) {event.preventDefault();zoom(1/1.3);}
    else if(event.key==='0') {event.preventDefault();fit('screen');}
  }
  function locateEntity() {
    const query=entityQuery.trim().toUpperCase();
    if(!query) {setEntityStatus('نام جدول را وارد کنید.');return;}
    const selected=entities.find(entity=>entity.label.toUpperCase()===query || entity.id.toUpperCase()===query)
      ?? entities.find(entity=>entity.label.toUpperCase().startsWith(query))
      ?? entities.find(entity=>entity.label.toUpperCase().includes(query));
    if(!selected) {setEntityStatus('این جدول در منبع نمودار پیدا نشد.');return;}
    if(focus && !focus.includedIds.has(selected.id)) {setEntityStatus(focusLabels.outside);return;}
    if(!canvas || !bounds) {setEntityStatus('نمودار هنوز آماده نمایش نیست.');return;}
    const point=svgEntityPoint(svg,engine,selected);
    if(!point) {
      setEntityStatus(`${selected.label} در منبع وجود دارد، اما مختصات آن در SVG این نوع نمودار قابل تشخیص نیست.`);
      return;
    }
    setEntityQuery(selected.label);
    const scaledPoint={x:point.x*canvas.width/bounds.width,y:point.y*canvas.height/bounds.height};
    setMarkedEntity(scaledPoint);
    custom();
    commit(focusAt(viewRef.current,scaledPoint,viewportSize(),Math.min(16,bounds.width/canvas.width)));
    setEntityStatus(`جدول ${selected.label} در مرکز نمایشگر قرار گرفت.`);
  }
  function chosenEntity() {
    const query=entityQuery.trim().toUpperCase();
    return entities.find(entity=>entity.label.toUpperCase()===query || entity.id.toUpperCase()===query)
      ?? entities.find(entity=>entity.label.toUpperCase().startsWith(query))
      ?? entities.find(entity=>entity.label.toUpperCase().includes(query));
  }
  function showFocus() {
    setFocusNotice('');
    if(!focusGraph) {setFocusNotice(focusLabels.unsupported);return;}
    const selected=chosenEntity();
    if(!selected) {setFocusNotice(entityQuery.trim()?focusLabels.missing:focusLabels.choose);return;}
    const result=buildFocusView(focusGraph,selected.id,focusDepth);
    if(!result.ok) {setFocusNotice(result.reason==='TOO_LARGE'?focusLabels.large:focusLabels.missing);return;}
    setEntityQuery(result.view.root.label);
    setFocus(result.view);
  }
  function changeDepth(value:1|2) {
    if(focus && focusGraph) {
      const result=buildFocusView(focusGraph,focus.root.id,value);
      if(!result.ok) {setFocusNotice(focusLabels.large);return;}
      setFocus(result.view);
    }
    setFocusDepth(value);setFocusNotice('');
  }
  function showAll() {setFocus(null);setFocusNotice('');setEntityStatus('');}
  async function toggleFullscreen() {
    if(nativeFullscreen) {
      try {await document.exitFullscreen();} catch {setFallbackExpanded(false);}
    } else if(fallbackExpanded) setFallbackExpanded(false);
    else if(rootRef.current?.requestFullscreen) {
      try {await rootRef.current.requestFullscreen();}
      catch {setFallbackExpanded(true);}
    } else setFallbackExpanded(true);
  }
  const expanded=nativeFullscreen || fallbackExpanded;
  const svgName=filename.replace(/\.(dot|gv|mmd)$/i,focus?`.focus-${focus.depth}.svg`:'.svg').replace(/[\\/:*?"<>|\x00-\x1f]/g,'_');
  return <div ref={rootRef} className={`diagram-render${fallbackExpanded?' diagram-expanded':''}`}>
    <div className="diagram-toolbar">
      <div className="diagram-controls" role="group" aria-label="کنترل نمایش نمودار">
        <button type="button" className={`btn secondary${viewMode==='screen'?' active':''}`} disabled={!url}
          onClick={()=>fit('screen')}>جا دادن در صفحه</button>
        <button type="button" className={`btn secondary${viewMode==='width'?' active':''}`} disabled={!url}
          onClick={()=>fit('width')}>جا دادن در عرض</button>
        <button type="button" className="btn secondary diagram-mini-btn" aria-label="بزرگ‌نمایی کمتر" disabled={!url}
          onClick={()=>zoom(1/1.3)}>−</button>
        <output className="diagram-zoom-output" aria-label="درصد بزرگ‌نمایی" dir="ltr">
          {(view.scale*100).toLocaleString('fa-IR',{maximumFractionDigits:1})}٪</output>
        <button type="button" className="btn secondary diagram-mini-btn" aria-label="بزرگ‌نمایی بیشتر" disabled={!url}
          onClick={()=>zoom(1.3)}>+</button>
        <button type="button" className="btn secondary" disabled={!url}
          onClick={()=>zoom(1/viewRef.current.scale)}>۱۰۰٪</button>
        <button type="button" className="btn secondary" disabled={!url} onClick={()=>fit('screen')}>بازنشانی</button>
        <button type="button" className="btn secondary" disabled={!url} onClick={()=>void toggleFullscreen()}>
          {expanded?'خروج از تمام‌صفحه':'تمام‌صفحه'}</button>
      </div>
      <button type="button" className="btn secondary" disabled={!svg}
        onClick={()=>saveDownload({filename:svgName,blob:new Blob([svg],{type:'image/svg+xml'})})}>
        دریافت SVG</button>
    </div>
    {entities.length>0 && <div className="diagram-entity-toolbar">
      <label className="diagram-entity-label" htmlFor={datalistId+'-input'}>یافتن جدول ({entities.length.toLocaleString('fa-IR')})</label>
      <input id={datalistId+'-input'} type="search" dir="ltr" list={datalistId} placeholder="SCHEMA.TABLE"
        value={entityQuery} onChange={event=>setEntityQuery(event.target.value)}
        onKeyDown={event=>{if(event.key==='Enter'){event.preventDefault();locateEntity();}}}/>
      <datalist id={datalistId}>{suggestions.map(entity=><option key={entity.id} value={entity.label}/>)}</datalist>
      <button type="button" className="btn secondary" disabled={!url} onClick={locateEntity}>پیدا کن</button>
      {entityStatus && <span role="status" className="diagram-search-status">{entityStatus}</span>}
    </div>}
    {entities.length>0 && <div className="diagram-focus-toolbar" role="group" aria-label={focusLabels.summary}>
      <button className={`btn secondary${focus?' active':''}`} type="button" disabled={!focusGraph || !url}
        onClick={showFocus}>{focusLabels.activate}</button>
      <label className="diagram-focus-depth">{focusLabels.depth}
        <select aria-label={focusLabels.depth} value={focusDepth}
          onChange={event=>changeDepth(Number(event.target.value)===2?2:1)} disabled={!focusGraph}>
          <option value={1}>{focusLabels.direct}</option><option value={2}>{focusLabels.two}</option>
        </select>
      </label>
      {focus && <button className="btn secondary" type="button" onClick={showAll}>{focusLabels.full}</button>}
      {focus && <p role="status" className="diagram-focus-status">
        {focusLabels.summary}: <b dir="ltr">{focus.root.label}</b> — {focus.nodeCount.toLocaleString('fa-IR')} {focusLabels.nodes},
        {' '}{focus.edgeCount.toLocaleString('fa-IR')} {focusLabels.edges}. {focusLabels.safe}
      </p>}
      {focusNotice && <p role="alert" className="diagram-focus-status">{focusNotice}</p>}
      {!focusGraph && <p className="diagram-focus-status">{focusLabels.unsupported}</p>}
    </div>}
    <p className="diagram-help">{engine==='mermaid'?'Mermaid':'Graphviz'} در مرورگر · چرخ ماوس: بزرگ‌نمایی روی نشانگر · کشیدن: جابه‌جایی · کلیدهای جهت‌دار، +، − و 0</p>
    {busy && <p role="status" className="loading">در حال ترسیم نمودار در مرورگر…</p>}
    {error && <p role="alert" className="message error">{error} فایل DOT/MMD اصلی همچنان قابل دریافت است.</p>}
    {url && canvas && <div ref={viewportRef} className={`diagram-viewport${dragging?' panning':''}`}
      role="region" aria-label={`نمایش تعاملی نمودار ${filename}`} tabIndex={0}
      onPointerDown={pointerStart} onPointerMove={pointerMove} onPointerUp={pointerEnd}
      onPointerCancel={pointerEnd} onKeyDown={keyboard}>
      <div className="diagram-canvas" style={{transform:`translate3d(${view.x}px,${view.y}px,0) scale(${view.scale})`}}>
        <img src={url} alt={`نمودار ${filename}`} draggable={false}
          style={{width:canvas.width,height:canvas.height}} referrerPolicy="no-referrer"/>
        {markedEntity && <span className="diagram-entity-marker" aria-hidden="true"
          style={{left:markedEntity.x,top:markedEntity.y,
            transform:`translate(-50%,-50%) scale(${1/view.scale})`}}/>}
      </div>
    </div>}
    <details><summary>نمایش متن اصلی نمودار</summary>
      <pre className="code-preview report-text" dir="ltr">{source}</pre>
    </details>
  </div>;
}
