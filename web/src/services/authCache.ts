import type { AuthSession } from '@/types/api';

let cachedAuthSession: AuthSession | undefined;
let authSessionVersion = 0;

export function getAuthSessionCache(): AuthSession | undefined {
  return cachedAuthSession;
}

export function setAuthSessionCache(session: AuthSession): void {
  cachedAuthSession = session;
  authSessionVersion += 1;
}

export function clearAuthSessionCache(): void {
  cachedAuthSession = undefined;
  authSessionVersion += 1;
}

export function getAuthSessionVersion(): number {
  return authSessionVersion;
}
