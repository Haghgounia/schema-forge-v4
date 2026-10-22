import { useEffect, useState } from 'react';
import OverviewView, {type ProjectSection} from './views/OverviewView';
import GenerationView from './views/GenerationView';
import ConformanceView from './views/ConformanceView';
import CrudView from './views/CrudView';
import DiagramView from './views/DiagramView';
import QueueView from './views/QueueView';
import TermsView from './views/TermsView';
import HistoryView from './views/HistoryView';
import GlossaryAdminView from './views/GlossaryAdminView';

type GlossarySection = 'terms' | 'queue' | 'history' | 'manage';
const topNav: {id: ProjectSection; title: string}[] = [
  {id: 'overview', title: 'خانه'}, {id: 'generation', title: 'تولید Schema'},
  {id: 'conformance', title: 'ممیزی دیتابیس'}, {id: 'crud', title: 'CRUD'},
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
      {active === 'overview' && <OverviewView navigate={navigate}/>}
      {active === 'generation' && <GenerationView/>}
      {active === 'conformance' && <ConformanceView/>}
      {active === 'crud' && <CrudView/>}
      {active === 'diagram' && <DiagramView/>}
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
          {route.tab === 'queue' && <QueueView adminKey={adminKey}/>}
          {route.tab === 'history' && <HistoryView adminKey={adminKey}/>}
          {route.tab === 'manage' && <GlossaryAdminView adminKey={adminKey} editId={editId}
            onSaved={() => setTermsVersion(version => version + 1)}/>}
        </div>
      </>}
      <footer className="footer"><span>SchemaForge · رابط کاربری عملیات REST</span>
        <span>اعتبارسنجی، دسترسی و ثبت نهایی در Backend انجام می‌شود.</span></footer>
    </main>
  </div>;
}
