import type { ApiErrorBody } from './types';

/** An API failure with the server's stable code. The UI shows the server's message, never its own guess. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly body?: ApiErrorBody;

  constructor(status: number, body: ApiErrorBody | undefined, fallback: string) {
    super(body?.message ?? fallback);
    this.status = status;
    this.code = body?.code ?? (status === 0 ? 'NETWORK' : `HTTP_${status}`);
    this.body = body;
  }
}

/** Optional bearer token provider (OIDC mode). In dev mode the session cookie authenticates. */
let tokenProvider: (() => Promise<string | undefined>) | undefined;

export function setTokenProvider(provider: (() => Promise<string | undefined>) | undefined) {
  tokenProvider = provider;
}

function csrfToken(): string | undefined {
  const match = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='));
  return match ? decodeURIComponent(match.substring('XSRF-TOKEN='.length)) : undefined;
}

export async function authHeaders(): Promise<Record<string, string>> {
  const headers: Record<string, string> = {};
  const token = tokenProvider ? await tokenProvider() : undefined;
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return headers;
}

async function parse<T>(response: Response): Promise<T> {
  const text = await response.text();
  const type = response.headers.get('content-type') ?? '';
  let body: unknown = undefined;
  if (text && type.includes('json')) {
    try {
      body = JSON.parse(text);
    } catch {
      body = undefined;
    }
  }
  if (!response.ok) {
    throw new ApiError(response.status, body as ApiErrorBody | undefined, `${response.status} ${response.statusText}`);
  }
  return (body === undefined ? (text as unknown) : body) as T;
}

export async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      credentials: 'same-origin',
      headers: { Accept: 'application/json', ...(await authHeaders()) },
      signal,
    });
  } catch (e) {
    if ((e as Error).name === 'AbortError') {
      throw e;
    }
    throw new ApiError(0, undefined, 'The Control Center API cannot be reached');
  }
  return parse<T>(response);
}

export async function getText(path: string, signal?: AbortSignal): Promise<string> {
  const response = await fetch(path, { credentials: 'same-origin', headers: await authHeaders(), signal });
  if (!response.ok) {
    return parse<string>(response);
  }
  return response.text();
}

/** A command. The Idempotency-Key makes a retried click return the first result instead of acting twice. */
export async function postJson<T>(path: string, body: unknown, idempotencyKey?: string): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'application/json',
    ...(await authHeaders()),
  };
  const csrf = csrfToken();
  if (csrf) {
    headers['X-XSRF-TOKEN'] = csrf;
  }
  if (idempotencyKey) {
    headers['Idempotency-Key'] = idempotencyKey;
  }
  let response: Response;
  try {
    response = await fetch(path, {
      method: 'POST',
      credentials: 'same-origin',
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, undefined, 'The Control Center API cannot be reached; the command may not have been received');
  }
  return parse<T>(response);
}

export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}
