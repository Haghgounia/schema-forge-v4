import { lazy, Suspense, useEffect, useState } from 'react';
import OverviewView, {type ProjectSection} from './views/OverviewView';
import type { ReviewHandoff } from './reviewHandoff';
import type { Download } from './transport';
import {setActiveMetadataConnection} from './transport';
import type {ActiveMetadataConnection} from './connectionApi';

const ConnectionsView = lazy(() => import('./views/ConnectionsView'));
const GenerationView = lazy(() => import('./views/GenerationView'));
const ConformanceView = lazy(() => import('./views/ConformanceView'));
const CrudView = lazy(() => import('./views/CrudView'));
const DiagramView = lazy(() => import('./views/DiagramView'));
const QueueView = lazy(() => import('./views/QueueView'));
const TermsView = lazy(() => import('./views/TermsView'));
const HistoryView = lazy(() => import('./views/HistoryView'));
const GlossaryAdminView = lazy(() => import('./views/GlossaryAdminView'));


type GlossarySection = 'terms' | 'queue' | 'history' | 'manage';
const topNav: {id: ProjectSection; title: string}[] = [
  {id: 'overview', title: 'خانه'}, {id: 'generation', title: 'تولید Schema'},
  {id: 'conformance', title: 'ممیزی دیتابیس'}, {id: 'connections', title: 'تنظیمات اتصال به دیتابیس'}, {id: 'crud', title: 'CRUD'},
  {id: 'diagram', title: 'نمودارها'}, {id: 'glossary', title: 'Glossary'},
];
const glossaryNav: {id: GlossarySection; title: string}[] = [
  {id: 'terms', title: 'واژه‌نامه'}, {id: 'queue', title: 'صف بازبینی'},
  {id: 'history', title: 'تاریخچه'}, {id: 'manage', title: 'مدیریت و ورود اطلاعات'},
];
function readRoute(): {section: ProjectSection; tab: GlossarySection} {
  const [primary, secondary] = window.location.hash.replace(/^#\/?/, '').split('/');
  const section = topNav.find(item => item.id === primary)?.id ?? 'overview';
  const tab = glossaryNav.find(item => item.id === secondary)?.id ?? 'terms';
  return {section, tab};
}
export default function App() {
  const [route, setRoute] = useState(readRoute);
  const [adminKey, setAdminKey] = useState('');
  const [editorKey, setEditorKey] = useState('');
  const [editId, setEditId] = useState<number|null>(null);
  const [termsVersion, setTermsVersion] = useState(0);
  const [reviewHandoff, setReviewHandoff] = useState<ReviewHandoff | null>(null);
  const [lastGenerationZip, setLastGenerationZip] = useState<Download | null>(null);
  const [activeConnection, setActiveConnectionState] = useState<ActiveMetadataConnection | null>(null);
  const [connectionSessions, setConnectionSessions] = useState<ActiveMetadataConnection[]>([]);
  function selectConnection(selection: ActiveMetadataConnection | null) {
    setActiveMetadataConnection(selection);
    setActiveConnectionState(selection);
    if (selection) setConnectionSessions(old => [...old.filter(item => item.token !== selection.token), selection]);
  }
  useEffect(() => {
    function updateRoute() { setRoute(readRoute()); }
    window.addEventListener('hashchange', updateRoute);
    return () => window.removeEventListener('hashchange', updateRoute);
  }, []);
  function navigate(section: ProjectSection, tab: GlossarySection = 'terms') {
    const hash = `#/${section}${section === 'glossary' ? `/${tab}` : ''}`;
    if (window.location.hash === hash) { setRoute({section, tab}); return; }
    window.location.hash = hash;
  }
  function goReview(handoff: ReviewHandoff) {
    setReviewHandoff(handoff); navigate('glossary', 'queue');
  }
  function editTerm(id: number) { setEditId(id); navigate('glossary', 'manage'); }
  const active = route.section;
  return <div className="app-shell" dir="rtl">
    <header className="app-header"><div className="container header-row">
      <button type="button" className="brand-button" onClick={() => navigate('overview')} aria-label="خانه SchemaForge">
        <span className="brand-symbol" aria-hidden="true">S</span>
        <span className="brand"><strong>SchemaForge</strong><small>Operations Console</small></span>
      </button>
      <div className="header-end"><span className="version">V4 · React UI</span>
        <a href="/swagger-ui.html" target="_blank" rel="noopener noreferrer" className="docs-link">REST API / Swagger ↗</a>
      </div>
    </div></header>
    <div className="main-navigation"><nav className="container nav-row" aria-label="بخش‌های اصلی">
      {topNav.map(item => <button type="button" key={item.id} className={`nav-button ${active === item.id ? 'selected' : ''}`}
        aria-current={active === item.id ? 'page' : undefined}
        onClick={() => navigate(item.id)}>{item.title}</button>)}
    </nav></div>
    <main className="container app-main">
      {activeConnection && <div className="message success metadata-active-banner" role="status">
        اتصال Metadata فعال: <strong dir="ltr">{activeConnection.platform.toUpperCase()}</strong>
        {activeConnection.jdbcTarget && <> · <span dir="ltr">{activeConnection.jdbcTarget}</span></>}
        {activeConnection.databaseName && <> · Database: <span dir="ltr">{activeConnection.databaseName}</span></>}
        {activeConnection.verifiedSchema && <> · Schema: <span dir="ltr">{activeConnection.verifiedSchema}</span></>}
        <button type="button" className="btn secondary" onClick={() => navigate('connections')}>Manage connection</button>
      </div>}
      <Suspense fallback={<p className="message neutral" role="status">در حال بارگذاری بخش انتخاب‌شده…</p>}>
      {active === 'overview' && <OverviewView navigate={navigate}/>}
      {active === 'generation' && <GenerationView activeMetadataPlatform={activeConnection?.platform} onReviewTerm={goReview} savedDownload={lastGenerationZip} onDownloadChange={setLastGenerationZip}/>}
      {active === 'conformance' && <ConformanceView activeConnection={activeConnection}
        connectionSessions={connectionSessions} onSelectConnection={selectConnection}
        onGoConnections={() => navigate('connections')}/>}
      {active === 'connections' && <ConnectionsView active={activeConnection} onSelectionChange={selectConnection}
        onDisconnected={token => setConnectionSessions(old => old.filter(item => item.token !== token))}/>}
      {active === 'crud' && <CrudView activeConnection={activeConnection} connectionSessions={connectionSessions}
        onSelectConnection={selectConnection} onGoConnections={() => navigate('connections')}/>}
      {active === 'diagram' && <DiagramView activeConnection={activeConnection} connectionSessions={connectionSessions}
        onSelectConnection={selectConnection} onGoConnections={() => navigate('connections')}/>}
      {active === 'glossary' && <>
        <div className="section-intro"><div><span className="eyebrow">Glossary Governance</span>
          <h1>فرهنگ واژگان و تصمیم‌گیری</h1>
          <p>۱۴ عملیات REST موجود، بدون تأیید خودکار و بدون تغییر مستقیم Canonical Model.</p></div></div>
        <nav className="tabs glossary-subnav" aria-label="بخش‌های Glossary">
          {glossaryNav.map(item => <button key={item.id} type="button"
            className={`tab ${route.tab === item.id ? 'active' : ''}`}
            aria-current={route.tab === item.id ? 'page' : undefined}
            onClick={() => navigate('glossary', item.id)}>{item.title}</button>)}
        </nav>
        <section className="access-strip" aria-label="کلید مدیر Glossary">
          <div className="access-label"><strong>دسترسی مدیر Glossary</strong>
            <span>فقط برای عملیات دارای مجوز سرور. کلید فقط در حافظه این صفحه نگهداری می‌شود.</span></div>
          <form className="access-form" onSubmit={e => { e.preventDefault(); setAdminKey(editorKey.trim()); setEditorKey(''); }}>
            <label className="sr-only" htmlFor="admin-key">کلید مدیر</label>
            <input id="admin-key" type="password" value={editorKey} autoComplete="off" spellCheck={false}
              placeholder={adminKey ? 'کلید فعال است' : 'کلید مدیر را وارد کنید'}
              onChange={e => setEditorKey(e.target.value)} />
            <button type="submit" className="btn primary" disabled={!editorKey.trim()}>اعمال</button>
            <button type="button" className="btn subtle" disabled={!adminKey && !editorKey}
              onClick={() => { setEditorKey(''); setAdminKey(''); }}>پاک‌کردن</button>
          </form>
        </section>
        <div className="workspace" key={route.tab}>
          {route.tab === 'terms' && <TermsView key={termsVersion} onEditTerm={editTerm}/>}
          {route.tab === 'queue' && <QueueView adminKey={adminKey} handoff={reviewHandoff} clearHandoff={() => setReviewHandoff(null)}/>}
          {route.tab === 'history' && <HistoryView adminKey={adminKey}/>}
          {route.tab === 'manage' && <GlossaryAdminView adminKey={adminKey} editId={editId}
            onSaved={() => setTermsVersion(version => version + 1)}/>}
        </div>
      </>}
      </Suspense>
      <footer className="footer"><span>SchemaForge · رابط کاربری عملیات REST</span>
        <span>اعتبارسنجی، دسترسی و ثبت نهایی در Backend انجام می‌شود.</span></footer>
    </main>
  </div>;
}
