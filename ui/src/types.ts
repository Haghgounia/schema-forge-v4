// Mirrors records in GlossaryController / GlossaryReviewService of SchemaForge 4.0.1.
// All writes remain validated and authorized on the Java server.
export type GlossaryKind = 'ATOMIC' | 'PHRASE';
export type GlossaryStatus = 'CANDIDATE' | 'APPROVED' | 'REJECTED';

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface GlossaryTerm {
  id: number;
  kind: GlossaryKind;
  key: string;
  english: string;
  persian: string;
  module: string;
  status: GlossaryStatus;
  source: string;
  createdAt: string;
  updatedAt: string;
}

export interface QueueItem {
  id: number;
  kind: GlossaryKind;
  key: string;
  occurrences: number;
  sampleSource: string;
  firstSeen: string;
  lastSeen: string;
  status: GlossaryStatus | 'DELETED';
}

export interface DecisionRequest {
  term: string;
  kind: GlossaryKind;
  to: 'APPROVED' | 'REJECTED';
  persian: string;
  module: string;
  reason: string;
}

export interface DecisionResult {
  id: number;
  kind: GlossaryKind;
  key: string;
  status: GlossaryStatus;
  reason: string;
}

export interface HistoryEvent {
  id: number;
  eventType: string;
  kind: GlossaryKind;
  termKey: string;
  statusBefore: string | null;
  statusAfter: string | null;
  reason: string;
  reportSha256: string | null;
  createdAt: string;
}

export interface ImportReportResult {
  reportSha256: string;
  sourceRows: number;
  newOccurrences: number;
  recordedOccurrences: number;
  previouslyRecorded: number;
  skippedNonNew: number;
  createdCandidates: number;
  alreadyKnownOccurrences: number;
  skippedKnownPluralOccurrences: number;
}

export interface SpellingAdvice {
  term: string;
  classification: string;
  canonicalSingular: string | null;
  canonicalStatus: GlossaryStatus | null;
  suggestions: {
    id: number;
    key: string;
    persian: string;
    status: GlossaryStatus;
    distance: number;
  }[];
  automaticChanges: boolean;
}

/** GlossaryTermRequest and native import/status REST result records. */
export interface GlossaryTermRequest {
  english: string;
  persian: string;
  module: string;
  kind: GlossaryKind;
  status: GlossaryStatus;
}
export interface ImportWorkbookResult {
  sourceRows: number;
  uniqueEntries: number;
  inserted: number;
  alreadyPresent: number;
  status: GlossaryStatus;
}
export interface BulkStatusResult {
  updated: number;
  kind: GlossaryKind | null;
  from: GlossaryStatus;
  to: GlossaryStatus;
}
