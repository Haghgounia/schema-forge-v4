export type ProjectSection = 'overview' | 'generation' | 'conformance' | 'crud' | 'diagram' | 'glossary';
const modules: { id: Exclude<ProjectSection, 'overview'>; heading: string; desc: string; detail: string }[] = [
  { id: 'generation', heading: 'تولید Schema', desc: 'Word، Legacy Word، Batch ZIP و EA/XMI', detail: 'پنج مسیر REST · دریافت ZIP' },
  { id: 'conformance', heading: 'ممیزی ساختار', desc: 'کنترل Table یا Schema موجود در DBMS', detail: 'فقط خواندنی · یک یا چند DBMS' },
  { id: 'crud', heading: 'تولید CRUD', desc: 'تولید اسکریپت از متادیتای Oracle و SQL Server', detail: 'دو مسیر REST · خروجی SQL' },
  { id: 'diagram', heading: 'نمودار Mermaid', desc: 'نمودار ER، وابستگی و مدل مفهومی', detail: 'Canonical JSON / ZIP' },
  { id: 'glossary', heading: 'فرهنگ واژگان', desc: 'جست‌وجو، صف تصمیم‌گیری، Import/Export و مدیریت', detail: '۱۴ عملیات REST موجود' },
];
export default function OverviewView({ navigate }: { navigate: (page: ProjectSection) => void }) {
  return <section className="overview">
    <div className="page-heading"><div><span className="eyebrow">SchemaForge Console</span>
      <h1>مدیریت سرویس‌های SchemaForge</h1>
      <p>اجرای فرآیندهای موجود از طریق REST API بدون انتقال منطق کسب‌وکار به React.</p></div></div>
    <div className="overview-grid">{modules.map(item => <button key={item.id} type="button" className="module-card"
      onClick={() => navigate(item.id)}>
      <strong>{item.heading}</strong><span>{item.desc}</span>
      <small>{item.detail}</small><span className="module-open">ورود به بخش ←</span>
    </button>)}</div>
    <p className="message neutral">این UI به Backend موجود وصل می‌شود. از آن برای مدیریت مستقیم دیتابیس یا جایگزین‌کردن اعتبارسنجی سمت سرور استفاده نمی‌شود. قابلیت‌های فاقد REST Endpoint در این نسخه نمایش عملیاتی ندارند.</p>
  </section>;
}
