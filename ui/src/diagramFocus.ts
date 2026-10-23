/** UI-C6e: a strictly read-only, source-preserving neighborhood view of generated ER diagrams.
 * We do not infer FK direction, cardinality, or missing relationships. Only recognized
 * SchemaForge DOT/erDiagram syntax is supported; unknown syntax fails closed to full view.
 */
import type { DiagramEngine } from './diagramRender.ts';
import type { DiagramEntity } from './diagramViewport.ts';

type GraphNode = DiagramEntity & { original: string };
type GraphEdge = { from: string; to: string; original: string };
export type DiagramFocusGraph = {
  engine: DiagramEngine;
  nodes: GraphNode[];
  edges: GraphEdge[];
  header: string;
};
export type DiagramFocusView = {
  source: string;
  root: DiagramEntity;
  depth: 1 | 2;
  nodeCount: number;
  edgeCount: number;
  includedIds: ReadonlySet<string>;
};
export type DiagramFocusAttempt =
  | { ok: true; view: DiagramFocusView }
  | { ok: false; reason: 'NOT_FOUND' | 'TOO_LARGE' };

const key = (value: string) => value.toUpperCase();
const isId = (value: string) => /^[\w.\-]{1,200}$/.test(value);
const MAX_FOCUS_NODES = 150;
const MAX_FOCUS_EDGES = 450;

function parseMermaid(source: string): DiagramFocusGraph | null {
  const lines = source.replace(/\r\n?/g, '\n').split('\n');
  if (lines[0]?.trim() !== 'erDiagram') return null;
  const aliases = new Map<string, string>();
  for (const line of lines) {
    const match = line.match(/^\s*%%\s*([A-Za-z_]\w*)\s*=\s*([\w.\-]+)\s*$/);
    if (match) aliases.set(match[1], match[2]);
  }
  const nodes: GraphNode[] = [];
  const edges: GraphEdge[] = [];
  let index = 1;
  while (index < lines.length) {
    const line = lines[index];
    if (!line.trim() || /^\s*%%/.test(line)) { index++; continue; }
    const block = line.match(/^\s*([A-Za-z_]\w*)\s*\{\s*$/);
    if (block) {
      const start = index;
      index++;
      while (index < lines.length && !/^\s*\}\s*$/.test(lines[index])) {
        // Attributes are text inside the entity block; never treat them as edges.
        if (/^[^\r\n]*\{/.test(lines[index])) return null;
        index++;
      }
      if (index === lines.length) return null;
      const id = block[1];
      const label = aliases.get(id) ?? id;
      if (!isId(label) || nodes.some(node => node.id === id)) return null;
      nodes.push({id, label, original: lines.slice(start, ++index).join('\n')});
      continue;
    }
    const relation = line.match(/^\s*([A-Za-z_]\w*)\s+(\S+)\s+([A-Za-z_]\w*)\s*:\s*(.*?)\s*$/);
    if (relation && /--|\.\./.test(relation[2]) && relation[4]) {
      edges.push({from:relation[1], to:relation[3], original:line});
      index++;
      continue;
    }
    // A new Mermaid statement shape must not quietly lose edges or labels.
    return null;
  }
  if (!nodes.length || nodes.some(node=>!isId(node.id))) return null;
  const ids = new Set(nodes.map(node=>node.id));
  if (edges.some(edge=>!ids.has(edge.from) || !ids.has(edge.to))) return null;
  return {engine:'mermaid', nodes, edges, header:'erDiagram'};
}

function parseGraphviz(source: string): DiagramFocusGraph | null {
  const lines = source.replace(/\r\n?/g,'\n').split('\n');
  const first = lines.findIndex(line=>!!line.trim());
  if (first < 0 || !/^\s*(?:strict\s+)?digraph\b[^{}]*\{\s*$/.test(lines[first])) return null;
  const nodes:GraphNode[] = [];
  const edges:GraphEdge[] = [];
  const header:string[] = [lines[first]];
  let seenStatement=false;
  let closed=false;
  let index=first+1;
  const nodeBegin = /^\s*"([^"\r\n]+)"\s*\[\s*label\s*=/;
  const edgeBegin = /^\s*"([^"\r\n]+)"\s*->\s*"([^"\r\n]+)"\s*\[/;
  for (;index<lines.length;) {
    const line=lines[index];
    if (/^\s*\}\s*$/.test(line)) {closed=true;index++;break;}
    if (!line.trim()) {if(!seenStatement) header.push(line);index++;continue;}
    const edge=line.match(edgeBegin);
    if(edge) {
      if (!/\]\s*;\s*$/.test(line)) return null;
      edges.push({from:edge[1],to:edge[2],original:line});
      seenStatement=true;index++;continue;
    }
    const node=line.match(nodeBegin);
    if(node) {
      const start=index;
      while (index<lines.length && !/\]\s*;\s*$/.test(lines[index])) index++;
      if(index===lines.length || !isId(node[1]) || nodes.some(item=>item.id===node[1])) return null;
      nodes.push({id:node[1],label:node[1],original:lines.slice(start,++index).join('\n')});
      seenStatement=true;continue;
    }
    if(!seenStatement && (/^\s*(?:graph|edge|node)\s*\[.*\]\s*;\s*$/.test(line) || /^\s*\/\//.test(line))) {
      header.push(line);index++;continue;
    }
    // This subset is deliberately limited to the generator's predictable syntax.
    return null;
  }
  if (!closed || lines.slice(index).some(line=>line.trim()) || !nodes.length) return null;
  const ids=new Set(nodes.map(node=>node.id));
  if(edges.some(edge=>!ids.has(edge.from)||!ids.has(edge.to))) return null;
  return {engine:'graphviz',nodes,edges,header:header.join('\n')};
}

/** A null result means the format is unsupported. The original full graph is still rendered. */
export function readFocusGraph(engine: DiagramEngine, source: string): DiagramFocusGraph | null {
  if(source.length > 256*1024) return null;
  return engine==='mermaid' ? parseMermaid(source) : parseGraphviz(source);
}

/** Traverse either end of each source edge, while retaining its ORIGINAL direction and labels. */
export function buildFocusView(graph:DiagramFocusGraph, rootName:string, depth:1|2):DiagramFocusAttempt {
  const root=graph.nodes.find(node=>key(node.id)===key(rootName) || key(node.label)===key(rootName));
  if(!root) return {ok:false,reason:'NOT_FOUND'};
  const adjacency=new Map<string,Set<string>>();
  for(const node of graph.nodes) adjacency.set(node.id,new Set());
  for(const edge of graph.edges) {
    adjacency.get(edge.from)?.add(edge.to);
    adjacency.get(edge.to)?.add(edge.from);
  }
  const included=new Set<string>([root.id]);
  let frontier=[root.id];
  for(let level=0;level<depth;level++) {
    const next:string[]=[];
    for(const id of frontier) for(const neighbor of adjacency.get(id) ?? []) {
      if(!included.has(neighbor)) {included.add(neighbor);next.push(neighbor);}
    }
    frontier=next;
  }
  const selectedEdges=graph.edges.filter(edge=>included.has(edge.from)&&included.has(edge.to));
  if(included.size>MAX_FOCUS_NODES || selectedEdges.length>MAX_FOCUS_EDGES) {
    return {ok:false,reason:'TOO_LARGE'};
  }
  const selectedNodes=graph.nodes.filter(node=>included.has(node.id));
  const filtered = graph.engine === 'mermaid'
    ? [graph.header,...selectedNodes.flatMap(node=>[`    %% ${node.id} = ${node.label}`,node.original,'']),
        ...selectedEdges.map(edge=>edge.original)].join('\n')+'\n'
    : [graph.header,...selectedNodes.map(node=>node.original),...selectedEdges.map(edge=>edge.original),'}'].join('\n')+'\n';
  return {ok:true,view:{source:filtered,root:{id:root.id,label:root.label},depth,
    nodeCount:included.size,edgeCount:selectedEdges.length,includedIds:included}};
}
