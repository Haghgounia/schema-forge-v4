import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {readFocusGraph,buildFocusView} from '../src/diagramFocus.ts';

const mermaid = `erDiagram
    %% SF_DPS_DEPOSIT_ACCOUNT = DPS.DEPOSIT_ACCOUNT
    SF_DPS_DEPOSIT_ACCOUNT {
      NUMBER_19_0 ACCOUNT_ID PK
    }
    %% SF_DPS_DEPOSIT_ACCOUNT_ATTRIBUTE = DPS.DEPOSIT_ACCOUNT_ATTRIBUTE
    SF_DPS_DEPOSIT_ACCOUNT_ATTRIBUTE {
      NUMBER_19_0 ATTRIBUTE_ID PK
    }
    %% SF_DPS_AUDIT = DPS.AUDIT
    SF_DPS_AUDIT {
      VARCHAR_30 REASON
    }
    %% SF_DPS_UNRELATED = DPS.UNRELATED
    SF_DPS_UNRELATED {
    }
    SF_DPS_DEPOSIT_ACCOUNT ||--o{ SF_DPS_DEPOSIT_ACCOUNT_ATTRIBUTE : "FK_ATTRIBUTE_ACCOUNT_ID"
    SF_DPS_DEPOSIT_ACCOUNT_ATTRIBUTE o|--o{ SF_DPS_AUDIT : "FK_AUDIT_ATTRIBUTE_ID"
`;
const dot = `digraph SchemaForge_ER {
 graph [rankdir=LR, overlap=false];
 edge [fontsize=10];
 node [shape=plain];
 "DPS.DEPOSIT_ACCOUNT" [label=<
 <TABLE BORDER="0"><TR><TD><B>DPS.DEPOSIT_ACCOUNT</B></TD></TR>
 <TR><TD>ACCOUNT_ID</TD><TD>NUMBER(19,0)</TD></TR></TABLE>
 >];
 "DPS.DEPOSIT_ACCOUNT_ATTRIBUTE" [label=<
 <TABLE BORDER="0"><TR><TD><B>DPS.DEPOSIT_ACCOUNT_ATTRIBUTE</B></TD></TR>
 <TR><TD>ATTRIBUTE_ID</TD><TD>NUMBER(19,0)</TD></TR></TABLE>
 >];
 "DPS.AUDIT" [label="DPS.AUDIT"];
 "DPS.UNRELATED" [label="DPS.UNRELATED"];
 "DPS.DEPOSIT_ACCOUNT_ATTRIBUTE" -> "DPS.DEPOSIT_ACCOUNT" [label="FK_ATTRIBUTE_ACCOUNT_ID"];
 "DPS.AUDIT" -> "DPS.DEPOSIT_ACCOUNT_ATTRIBUTE" [label="FK_AUDIT_ATTRIBUTE_ID"];
}
`;

test('Mermaid direct neighbor preserves alias, columns, original FK cardinality and source',()=>{
 const model=readFocusGraph('mermaid',mermaid);
 assert.ok(model);
 const result=buildFocusView(model,'DPS.DEPOSIT_ACCOUNT',1);
 assert.ok(result.ok);
 assert.equal(result.view.nodeCount,2);
 assert.equal(result.view.edgeCount,1);
 assert.match(result.view.source,/NUMBER_19_0 ATTRIBUTE_ID PK/);
 assert.match(result.view.source,/\|\|--o\{/);
 assert.match(result.view.source,/FK_ATTRIBUTE_ACCOUNT_ID/);
 assert.doesNotMatch(result.view.source,/SF_DPS_AUDIT/);
 assert.doesNotMatch(result.view.source,/SF_DPS_UNRELATED/);
 assert.equal(mermaid.includes('DPS.UNRELATED'),true,'original source must not be mutated');
 assert.equal(readFocusGraph('mermaid',result.view.source)?.nodes.length,2);
});

test('Mermaid two hops includes one extra layer, but not isolated tables',()=>{
 const model=readFocusGraph('mermaid',mermaid);
 const one=buildFocusView(model,'DPS.DEPOSIT_ACCOUNT',1);
 const two=buildFocusView(model,'DPS.DEPOSIT_ACCOUNT',2);
 assert.ok(one.ok&&two.ok);
 assert.equal(two.view.nodeCount,3);
 assert.equal(two.view.edgeCount,2);
 assert.match(two.view.source,/FK_AUDIT_ATTRIBUTE_ID/);
 assert.doesNotMatch(two.view.source,/DPS.UNRELATED/);
 assert.deepEqual([...one.view.includedIds].sort(),[
   'SF_DPS_DEPOSIT_ACCOUNT','SF_DPS_DEPOSIT_ACCOUNT_ATTRIBUTE']);
});

test('Graphviz HTML labels and original directed FK edges survive focus',()=>{
 const graph=readFocusGraph('graphviz',dot);
 assert.ok(graph);
 const focus=buildFocusView(graph,'DPS.DEPOSIT_ACCOUNT',1);
 assert.ok(focus.ok);
 assert.equal(focus.view.nodeCount,2);
 assert.equal(focus.view.edgeCount,1);
 assert.match(focus.view.source,/<TABLE BORDER="0">/);
 assert.match(focus.view.source,/"DPS.DEPOSIT_ACCOUNT_ATTRIBUTE" -> "DPS.DEPOSIT_ACCOUNT"/);
 assert.match(focus.view.source,/FK_ATTRIBUTE_ACCOUNT_ID/);
 assert.doesNotMatch(focus.view.source,/DPS.UNRELATED/);
 assert.equal(readFocusGraph('graphviz',focus.view.source)?.edges.length,1);
});

test('Graphviz 2-hop focus retains both FK lines and excludes disconnected nodes',()=>{
 const model=readFocusGraph('graphviz',dot);
 const result=buildFocusView(model,'DPS.DEPOSIT_ACCOUNT',2);
 assert.ok(result.ok);
 assert.equal(result.view.nodeCount,3);
 assert.equal(result.view.edgeCount,2);
 assert.doesNotMatch(result.view.source,/DPS.UNRELATED/);
 assert.match(result.view.source,/FK_AUDIT_ATTRIBUTE_ID/);
});

test('Exact table identity avoids prefix confusion and missing table fails closed',()=>{
 const graph=readFocusGraph('mermaid',mermaid);
 const focus=buildFocusView(graph,'DPS.DEPOSIT_ACCOUNT_ATTRIBUTE',1);
 assert.ok(focus.ok);
 assert.equal(focus.view.root.label,'DPS.DEPOSIT_ACCOUNT_ATTRIBUTE');
 assert.equal(buildFocusView(graph,'DPS.DEPOSIT_ACCOUNT_ATTR',1).ok,false);
});

test('Self-loop and duplicate FK labels remain unchanged in the scoped source',()=>{
 const source=mermaid + '    SF_DPS_AUDIT o|--o| SF_DPS_AUDIT : "FK_AUDIT_PARENT"\n';
 const graph=readFocusGraph('mermaid',source);
 const focus=buildFocusView(graph,'DPS.AUDIT',1);
 assert.ok(focus.ok);
 assert.match(focus.view.source,/FK_AUDIT_PARENT/);
 assert.ok(focus.view.includedIds.has('SF_DPS_AUDIT'));
});

test('Unsupported or malformed graph syntax never yields a deceptively incomplete view',()=>{
 assert.equal(readFocusGraph('mermaid','graph TD\n  A-->B'),null);
 assert.equal(readFocusGraph('mermaid','erDiagram\n A {\n }\n A -- B'),null);
 assert.equal(readFocusGraph('graphviz','digraph X {\n subgraph cluster_0 { A; }\n }'),null);
 assert.equal(readFocusGraph('graphviz','digraph X {\n "A" [label="A"];\n "A" -> "B";\n}'),null);
});

test('Large neighborhoods are refused rather than silently dropping relationships',()=>{
 const lines=['erDiagram'];
 for(let i=0;i<153;i++) lines.push(` X${i} {`,` }`);
 for(let i=1;i<153;i++) lines.push(` X0 ||--o{ X${i} : "FK_${i}"`);
 const graph=readFocusGraph('mermaid',lines.join('\n'));
 assert.ok(graph);
 const attempt=buildFocusView(graph,'X0',1);
 assert.deepEqual(attempt,{ok:false,reason:'TOO_LARGE'});
});

test('UI remains read-only and re-renders a filtered source rather than clipping the SVG',()=>{
 const code=readFileSync(new URL('../src/components/RenderedDiagram.tsx',import.meta.url),'utf8');
 for(const token of ['readFocusGraph','buildFocusView','visibleSource','setFocus','changeDepth','showAll','svgEntityPoint']) {
   assert.ok(code.includes(token),`missing ${token}`);
 }
 assert.ok(code.includes('renderDiagramSvg(engine,visibleSource)'));
 assert.ok(!code.includes('dangerouslySetInnerHTML'));
 assert.ok(!code.includes('fetch('));
});
