import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { canvasBounds, diagramEntities, fitDiagram, focusAt, svgBounds, svgTransform, applyTransform,
  zoomAt } from '../src/diagramViewport.ts';

const DOT = `digraph SchemaForge_ER {
  graph [rankdir=LR];
  node [shape=box];
  "CLT.COLLATERAL" [label=<
     <TABLE><TR><TD>CLT.COLLATERAL</TD></TR></TABLE>
  >];
  "CLT.REF_CURRENCY" [label="CLT.REF_CURRENCY"];
  "CLT.REF_CURRENCY" -> "CLT.COLLATERAL";
}`;
const MMD = `erDiagram
  %% SF_CLT_COLLATERAL = CLT.COLLATERAL
  SF_CLT_COLLATERAL {
    NUMBER COLLATERAL_ID PK
  }
  %% SF_CLT_REF_CURRENCY = CLT.REF_CURRENCY
  SF_CLT_REF_CURRENCY {
  }
  SF_CLT_REF_CURRENCY ||--o{ SF_CLT_COLLATERAL : relates
`;

test('Graphviz and Mermaid entity indexes are grounded in the generated source declarations', () => {
  assert.deepEqual(diagramEntities('graphviz',DOT).map(v=>v.label),['CLT.COLLATERAL','CLT.REF_CURRENCY']);
  assert.deepEqual(diagramEntities('mermaid',MMD),[
    {id:'SF_CLT_COLLATERAL',label:'CLT.COLLATERAL'},
    {id:'SF_CLT_REF_CURRENCY',label:'CLT.REF_CURRENCY'},
  ]);
  assert.equal(diagramEntities('graphviz','digraph { A -> B; }').length,0);
});

test('SVG viewBox is used for real vector bounds; pt fallback and invalid input are safe', () => {
  assert.deepEqual(svgBounds('<svg width="4362pt" height="858pt" viewBox="0.00 0.00 4362.00 858.00">'),
    {minX:0,minY:0,width:4362,height:858});
  assert.deepEqual(svgBounds('<svg height="72pt" width="144pt">'),
    {minX:0,minY:0,width:192,height:96});
  assert.equal(svgBounds('<svg width="100%" height="300">'),null);
  assert.equal(svgBounds('<svg viewBox="0 0 0 100">'),null);
  assert.equal(svgBounds('<script>not-an-svg</script>'),null);
});

test('screen/width fit, native zoom, cursor anchored wheel and focused entity use independent vector coordinates', () => {
  const art={minX:0,minY:0,width:4362,height:858};
  const frame={x:1200,y:580};
  const screen=fitDiagram(frame,art,'screen');
  assert.ok(screen.scale<.28 && screen.scale>.26);
  assert.ok(screen.x>=0 && screen.y>=0);
  const vertical={...art,width:200,height:2000};
  const tall=fitDiagram(frame,vertical,'screen');
  const width=fitDiagram(frame,vertical,'width');
  assert.ok(width.scale>tall.scale);
  assert.equal(width.y,20);
  const anchor={x:600,y:300};
  const zoomed=zoomAt(screen,2,anchor);
  const px=(anchor.x-screen.x)/screen.scale,py=(anchor.y-screen.y)/screen.scale;
  assert.ok(Math.abs(zoomed.x+px*zoomed.scale-anchor.x)<1e-6);
  assert.ok(Math.abs(zoomed.y+py*zoomed.scale-anchor.y)<1e-6);
  const focused=focusAt(screen,{x:1780,y:650},frame,1);
  assert.equal(focused.scale,1);
  assert.equal(focused.x+1780,600);
  assert.equal(focused.y+650,290);
});

test('Graphviz SVG coordinate transforms from real DOT output remain navigable', () => {
  const transform=svgTransform('scale(1 1) rotate(0) translate(4 854)');
  assert.deepEqual(applyTransform(transform,{x:1780,y:-200}),{x:1784,y:654});
  const scaled=svgTransform('translate(10,20) scale(2)');
  assert.deepEqual(applyTransform(scaled,{x:3,y:4}),{x:16,y:28});
  const reduced=canvasBounds({minX:0,minY:0,width:32000,height:1000});
  assert.equal(reduced.width,16000);
  assert.equal(reduced.height,500);
});

test('UI uses common viewer, pointer interaction and fullscreen without new REST calls', () => {
  const component=readFileSync(new URL('../src/components/RenderedDiagram.tsx',import.meta.url),'utf8');
  const generation=readFileSync(new URL('../src/components/GenerationDiagrams.tsx',import.meta.url),'utf8');
  const diagram=readFileSync(new URL('../src/views/DiagramView.tsx',import.meta.url),'utf8');
  for (const token of ['fitDiagram','zoomAt','onPointerDown','requestFullscreen','svgEntityPoint','جا دادن در عرض','بازنشانی']) {
    assert.ok(component.includes(token),`missing ${token}`);
  }
  assert.ok(!component.includes('dangerouslySetInnerHTML'));
  assert.ok(generation.includes('<RenderedDiagram'));
  assert.ok(diagram.includes('<RenderedDiagram'));
});
