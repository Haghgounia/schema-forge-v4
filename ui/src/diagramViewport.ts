/** Browser-only diagram navigation mathematics. Rendering stays in an isolated <img> SVG context. */
import type { DiagramEngine } from './diagramRender';

export type Point = {x: number; y: number};
export type Bounds = {width: number; height: number; minX: number; minY: number};
export type View = Point & {scale: number};
export type DiagramEntity = {id: string; label: string};
export type FitMode = 'screen' | 'width';

type Matrix = {a:number; b:number; c:number; d:number; e:number; f:number};
const ID: Matrix = {a:1,b:0,c:0,d:1,e:0,f:0};
const NUMBER = /[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?/g;
const finitePositive = (v:number) => Number.isFinite(v) && v > 0;
const normalize = (text:string) => text.trim().toUpperCase();

/** viewBox dimensions are SVG user units; CSS uses those same units at 100% navigation zoom. */
export function svgBounds(source: string): Bounds | null {
  const root = source.match(/<svg\b[^>]*>/i)?.[0];
  if (!root) return null;
  const box = root.match(/\bviewBox\s*=\s*["']([^"']+)["']/i)?.[1];
  if (box) {
    const numbers = box.match(NUMBER)?.map(Number);
    if (numbers?.length === 4 && numbers.every(Number.isFinite) &&
        finitePositive(numbers[2]) && finitePositive(numbers[3])) {
      return {minX:numbers[0],minY:numbers[1],width:numbers[2],height:numbers[3]};
    }
  }
  const axis = (name: 'width' | 'height') => {
    const attr = root.match(new RegExp(`\\b${name}\\s*=\\s*["']([^"']+)["']`, 'i'))?.[1] ?? '';
    const match = attr.match(/^\s*(\d+(?:\.\d+)?)(px|pt)?\s*$/i);
    return match ? Number(match[1]) * (match[2]?.toLowerCase() === 'pt' ? 96 / 72 : 1) : NaN;
  };
  const width = axis('width'); const height = axis('height');
  return finitePositive(width) && finitePositive(height) ? {minX:0,minY:0,width,height} : null;
}

/** CSS width limits prevent browsers from silently capping extremely large SVG elements. */
export function canvasBounds(bounds: Bounds, maxSide = 16000): Bounds {
  const factor = Math.min(1, maxSide / Math.max(bounds.width,bounds.height));
  return {minX:0,minY:0,width:bounds.width*factor,height:bounds.height*factor};
}

export function fitDiagram(viewport: Point, image: Bounds, mode: FitMode, margin = 20): View {
  const availableWidth = Math.max(1, viewport.x - 2*margin);
  const availableHeight = Math.max(1, viewport.y - 2*margin);
  const widthScale = availableWidth / image.width;
  const heightScale = availableHeight / image.height;
  const scale = Math.min(16, Math.max(0.0005, mode === 'screen' ? Math.min(widthScale,heightScale) : widthScale));
  return {
    scale,
    x:(viewport.x - image.width*scale)/2,
    y:mode === 'screen' ? (viewport.y - image.height*scale)/2 : margin,
  };
}

export function zoomAt(view: View, multiplier: number, anchor: Point): View {
  const scale = Math.max(0.0005,Math.min(16,view.scale*multiplier));
  const ratio = scale/view.scale;
  return {scale,x:anchor.x-(anchor.x-view.x)*ratio,y:anchor.y-(anchor.y-view.y)*ratio};
}

export function focusAt(view: View, point: Point, viewport: Point, minScale = 1): View {
  const scale = Math.max(view.scale, Math.min(16,minScale));
  return {scale,x:viewport.x/2-point.x*scale,y:viewport.y/2-point.y*scale};
}

/** Names come from generated DOT nodes or Mermaid declarations, not guessed SQL names. */
export function diagramEntities(engine: DiagramEngine, source: string): DiagramEntity[] {
  const found = new Map<string, DiagramEntity>();
  function add(id: string, label = id) {
    if (!/^[\w.\-]{1,200}$/.test(id) || !/^[\w.\-]{1,200}$/.test(label)) return;
    const key = normalize(label);
    if (!found.has(key) && found.size < 5000) found.set(key,{id,label});
  }
  if (engine === 'graphviz') {
    for (const match of source.matchAll(/^\s*"([^"\r\n]+)"\s*\[\s*label\s*=/gm)) add(match[1]);
    for (const match of source.matchAll(/^\s*([A-Za-z_][\w.]*)\s*\[\s*label\s*=/gm)) {
      if (!['graph','node','edge'].includes(match[1].toLowerCase())) add(match[1]);
    }
  } else {
    const aliases = new Map<string,string>();
    for (const match of source.matchAll(/^\s*%%\s*([\w]+)\s*=\s*([\w.]+)\s*$/gm)) aliases.set(match[1],match[2]);
    for (const match of source.matchAll(/^\s*([A-Za-z_][\w]*)\s*\{/gm)) add(match[1],aliases.get(match[1]) ?? match[1]);
    // Some Mermaid Dependency diagrams contain only relationships, not entity blocks.
    if (found.size === 0) {
      for (const match of source.matchAll(/^\s*([A-Za-z_][\w.]*)\s+(?:\|\||o\||\|o|\}o|--|\.\.)/gm)) {
        add(match[1],aliases.get(match[1]) ?? match[1]);
      }
    }
  }
  return [...found.values()];
}

function multiply(a: Matrix,b: Matrix):Matrix {
  return {a:a.a*b.a+a.c*b.b,b:a.b*b.a+a.d*b.b,
    c:a.a*b.c+a.c*b.d,d:a.b*b.c+a.d*b.d,
    e:a.a*b.e+a.c*b.f+a.e,f:a.b*b.e+a.d*b.f+a.f};
}
export function svgTransform(value:string): Matrix {
  let current = ID;
  for (const item of value.matchAll(/([A-Za-z]+)\s*\(([^)]*)\)/g)) {
    const n = item[2].match(NUMBER)?.map(Number) ?? [];
    let part:Matrix = ID;
    switch (item[1].toLowerCase()) {
      case 'matrix':
        if(n.length === 6 && n.every(Number.isFinite)) part = {a:n[0],b:n[1],c:n[2],d:n[3],e:n[4],f:n[5]};
        break;
      case 'translate':
        if(n.length >= 1) part={...ID,e:n[0],f:n[1] ?? 0};
        break;
      case 'scale':
        if(n.length >= 1) part={...ID,a:n[0],d:n[1] ?? n[0]};
        break;
      case 'rotate':
        if(n.length >= 1) {
          const radians = n[0]*Math.PI/180, cos=Math.cos(radians), sin=Math.sin(radians);
          const rotation:Matrix={...ID,a:cos,b:sin,c:-sin,d:cos};
          part=n.length >= 3 ? multiply(multiply({...ID,e:n[1],f:n[2]},rotation),{...ID,e:-n[1],f:-n[2]}) : rotation;
        }
        break;
      default: break;
    }
    current=multiply(current,part);
  }
  return current;
}
export function applyTransform(matrix:Matrix,point:Point):Point {
  return {x:matrix.a*point.x+matrix.c*point.y+matrix.e,
    y:matrix.b*point.x+matrix.d*point.y+matrix.f};
}

/** Compare rendered SVG identifiers by whole entity name, not substring.
 * Mermaid node IDs are implementation details and may be sequential numbers rather than
 * the source alias, so text labels provide a second, exact-match path.
 */
function svgIdentifierMatches(value:string, entityName:string):boolean {
  const id=normalize(value),name=normalize(entityName);
  return !!name && (id===name || id===`ENTITY-${name}` || id.startsWith(`ENTITY-${name}-`) ||
    id===`FLOWCHART-${name}` || id.startsWith(`FLOWCHART-${name}-`));
}

function groupHasShape(group:Element):boolean {
  return group.querySelector('rect, polygon, ellipse, circle') !== null;
}

/** Find a nearby group with a node shape; exclude diagram-wide groups containing many nodes.
 * A Mermaid label may be in <text>, nested <tspan>, or an HTML foreignObject.
 */
function containingEntityGroup(element:Element, svgRoot:Element):Element|null {
  let fallback:Element|null=null;
  for(let current:Element|null=element;current && current!==svgRoot;current=current.parentElement) {
    if(current.localName!=='g') continue;
    if (current.classList.contains('node') || current.classList.contains('entity') ||
      current.querySelector(':scope > rect.entityBox, :scope > rect.er, :scope > rect, :scope > polygon, :scope > ellipse')) {
      return current;
    }
    if(!fallback && groupHasShape(current)) fallback=current;
  }
  return fallback;
}

/** Parse SVG as inert XML and navigate using ID, exact label or Graphviz title.
 * Never insert untrusted SVG markup into the page DOM.
 */
export function svgEntityPoint(svg: string, engine:DiagramEngine, entity:DiagramEntity):Point | null {
  if (typeof DOMParser === 'undefined') return null;
  try {
    const doc = new DOMParser().parseFromString(svg,'image/svg+xml');
    if (doc.querySelector('parsererror')) return null;
    const root=doc.documentElement;
    if(root.localName!=='svg') return null;
    const names=[entity.id,entity.label].map(normalize).filter(Boolean);
    const groups=Array.from(doc.querySelectorAll('g'));
    let group:Element|undefined;
    if(engine==='graphviz') {
      group=groups.find(g => g.classList.contains('node') &&
        names.includes(normalize(Array.from(g.children).find(child=>child.localName==='title')?.textContent ?? '')));
    } else {
      // Older Mermaid uses entity-SOURCE_NAME-17; modern renderers can use opaque IDs.
      group=groups.find(g => ['id','data-id','data-node-id'].some(attribute =>
        names.some(name => svgIdentifierMatches(g.getAttribute(attribute) ?? '',name))));
    }
    if(!group) {
      // Some Mermaid versions give every node an opaque id, but preserve its
      // source alias in the entity label. An exact match prevents prefix collisions.
      const labels=Array.from(doc.querySelectorAll('text, tspan, title, foreignObject, span, p'));
      for(const label of labels) {
        if(!names.includes(normalize(label.textContent ?? ''))) continue;
        const candidate=containingEntityGroup(label,root);
        if(candidate) {group=candidate;break;}
      }
    }
    if(!group) return null;
    const geometry=group.querySelector('rect.entityBox, rect, polygon, ellipse, circle, text');
    let local:Point={x:0,y:0};
    if(geometry) {
      const num=(attribute:string) => Number(geometry.getAttribute(attribute) ?? '0');
      switch(geometry.localName) {
        case 'polygon': {
          const values=geometry.getAttribute('points')?.match(NUMBER)?.map(Number) ?? [];
          if(values.length>=4) {
            const xs=values.filter((_,i)=>i%2===0),ys=values.filter((_,i)=>i%2===1);
            local={x:(Math.min(...xs)+Math.max(...xs))/2,y:(Math.min(...ys)+Math.max(...ys))/2};
          }
          break;
        }
        case 'rect': local={x:num('x')+num('width')/2,y:num('y')+num('height')/2}; break;
        case 'ellipse':case 'circle':local={x:num('cx'),y:num('cy')};break;
        case 'text':local={x:num('x'),y:num('y')};break;
        default:break;
      }
    }
    let matrix=ID;
    for(let node:Element|null=geometry ?? group;node;node=node.parentElement) {
      matrix=multiply(svgTransform(node.getAttribute('transform') ?? ''),matrix);
      if(node===root) break;
    }
    const point=applyTransform(matrix,local), bounds=svgBounds(svg);
    if(!bounds || !Number.isFinite(point.x) || !Number.isFinite(point.y)) return null;
    const x=point.x-bounds.minX,y=point.y-bounds.minY;
    // Reject corrupted geometry; never jump to a different entity.
    if(x < -bounds.width*.1 || x>bounds.width*1.1 || y < -bounds.height*.1 || y>bounds.height*1.1) return null;
    return {x,y};
  } catch {return null;}
}
