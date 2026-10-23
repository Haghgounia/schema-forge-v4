import { test } from 'node:test';
import assert from 'node:assert/strict';
import { svgEntityPoint } from '../src/diagramViewport.ts';

// Tiny inert XML DOM double: exercises the production SVG search without adding
// a browser DOM dependency to the 0.4.x npm test suite. Real Chromium checks are
// kept separately in delivery QA. Only the selectors actually used by the locator
// are implemented; this is not a general XML parser.
class E {
  constructor(localName, attributes, parentElement=null) {
    this.localName=localName;
    this.attributes=attributes;
    this.parentElement=parentElement;
    this.children=[];
    this.text='';
    this.classList={contains:(name)=>(attributes.class??'').split(/\s+/).includes(name)};
  }
  getAttribute(key) {return this.attributes[key]??null;}
  get textContent() {return this.text+this.children.map(child=>child.textContent).join('');}
  querySelectorAll(selector) {
    const specs=selector.split(',').map(item=>item.trim());
    function nodeMatches(node,spec) {
      const scope=spec.startsWith(':scope > ');
      const single=spec.replace(/^:scope > /,'');
      const [type,className]=single.split('.');
      return node.localName===type && (!className || node.classList.contains(className)) && !scope;
    }
    const matches=[];
    function walk(parent) {
      for(const child of parent.children) {
        if(specs.some(spec=>nodeMatches(child,spec))) matches.push(child);
        walk(child);
      }
    }
    for(const spec of specs.filter(item=>item.startsWith(':scope > '))) {
      for(const child of this.children) {
        const [type,klass]=spec.slice(9).split('.');
        if(child.localName===type && (!klass || child.classList.contains(klass)) && !matches.includes(child)) matches.push(child);
      }
    }
    walk(this);
    return matches;
  }
  querySelector(selector) {return this.querySelectorAll(selector)[0]??null;}
}

function parseFixture(xml) {
  const nodes=[];let current=null;let root=null;
  for(const part of xml.match(/<[^>]+>|[^<]+/g)??[]) {
    if(part.startsWith('</')) {current=nodes.pop()??null;continue;}
    if(part.startsWith('<')) {
      const name=part.match(/^<([\w:-]+)/)?.[1];
      if(!name) continue;
      const attributes={};
      for(const a of part.matchAll(/([\w:-]+)\s*=\s*["']([^"']*)["']/g)) attributes[a[1]]=a[2];
      const element=new E(name,attributes,current);
      if(current) current.children.push(element);
      else if(!root) root=element;
      if(!part.endsWith('/>')) {nodes.push(current);current=element;}
    } else if(current) current.text+=part;
  }
  if(!root) throw new Error('Invalid test XML');
  return {documentElement:root,querySelector:(selector)=>root.querySelector(selector),
    querySelectorAll:(selector)=>selector==='g'?(root.localName==='g'?[root]:[]).concat(root.querySelectorAll('g')):root.querySelectorAll(selector)};
}

const original=globalThis.DOMParser;
globalThis.DOMParser=class DOMParserDouble {
  parseFromString(svg,mime) {
    assert.equal(mime,'image/svg+xml');
    return parseFixture(svg);
  }
};

const entity={id:'SF_DPS_DEPOSIT_ACCOUNT_ATTRIBUTE',label:'DPS.DEPOSIT_ACCOUNT_ATTRIBUTE'};
const root=(body)=>`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1800 1000">${body}</svg>`;

test('Mermaid regression: opaque SVG node id with exact source label still locates DPS table',()=>{
  const svg=root(`<g transform="translate(20,30)"><g id="entity-42" class="node default" transform="translate(300,140)">
    <rect class="entityBox" x="0" y="0" width="200" height="80"/>
    <g class="label"><text>SF_DPS_DEPOSIT_ACCOUNT_ATTRIBUTE</text></g></g></g>`);
  assert.deepEqual(svgEntityPoint(svg,'mermaid',entity),{x:420,y:210});
});

test('Mermaid entity-SOURCE_NAME-ID paths still locate and ignore similar prefixes',()=>{
  const svg=root(`<g id="entity-SF_DPS_DEPOSIT_ACCOUNT_ATTRIBUTE_DETAIL-11" transform="translate(600,100)">
      <rect x="0" y="0" width="100" height="40"/></g>
    <g id="entity-SF_DPS_DEPOSIT_ACCOUNT_ATTRIBUTE-12" transform="translate(200,300)">
      <rect x="0" y="0" width="120" height="50"/></g>`);
  assert.deepEqual(svgEntityPoint(svg,'mermaid',entity),{x:260,y:325});
});

test('Mermaid nested foreignObject labels can be used for node navigation',()=>{
  const svg=root(`<g class="node" id="hidden-4" transform="translate(150 200)">
    <rect x="-100" y="-60" width="200" height="120"/>
    <g class="label"><foreignObject><div><span>DPS.DEPOSIT_ACCOUNT_ATTRIBUTE</span></div></foreignObject></g></g>`);
  assert.deepEqual(svgEntityPoint(svg,'mermaid',entity),{x:150,y:200});
});

test('Graphviz title lookup survives the Mermaid fallback',()=>{
  const svg=root(`<g transform="translate(4 290)"><g class="node">
    <title>DPS.DEPOSIT_ACCOUNT_ATTRIBUTE</title>
    <polygon points="100,-150 200,-150 200,-100 100,-100"/></g></g>`);
  assert.deepEqual(svgEntityPoint(svg,'graphviz',entity),{x:154,y:165});
});

test('A similar-but-not-exact table name must not move the view to another node',()=>{
  const svg=root(`<g class="node" id="entity-42" transform="translate(200,300)"><rect width="120" height="50"/>
      <text>SF_DPS_DEPOSIT_ACCOUNT_ATTRIBUTE_DETAIL</text></g>`);
  assert.equal(svgEntityPoint(svg,'mermaid',entity),null);
});

test('Out-of-range node geometry is rejected rather than jumping to the wrong place',()=>{
  const svg=root(`<g class="node" id="entity-42" transform="translate(90000,300)"><rect width="120" height="50"/>
      <text>SF_DPS_DEPOSIT_ACCOUNT_ATTRIBUTE</text></g>`);
  assert.equal(svgEntityPoint(svg,'mermaid',entity),null);
});

test.after(()=>{if(original===undefined) delete globalThis.DOMParser; else globalThis.DOMParser=original;});
