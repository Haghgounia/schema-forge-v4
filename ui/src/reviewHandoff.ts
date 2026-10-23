import { reviewCsvFromZip, type ZipInspection } from './zipReports.ts';

/** Transient UI-only handoff. File and secret are never put in the URL/storage. */
export type ReviewHandoff = {
  term: string;
  sourceName: string;
  newTermCount: number;
  report: File | null;
  note?: string;
};

export async function prepareReviewHandoff(inspection: ZipInspection, term: string): Promise<ReviewHandoff> {
  const base = {
    term,
    sourceName: inspection.manifest?.source?.name ?? 'ZIP',
    newTermCount: inspection.glossary?.uniqueNewTerms ?? 0,
  };
  try {
    const report = await reviewCsvFromZip(inspection);
    return { ...base, report,
      note: report ? undefined : 'این ZIP گزارش unknown-terms.csv ندارد؛ فقط جست‌وجوی واژه در H2 ممکن است.' };
  } catch (error) {
    return { ...base, report: null,
      note: error instanceof Error ? error.message : 'فایل CSV قابل انتقال به صف نیست.' };
  }
}
