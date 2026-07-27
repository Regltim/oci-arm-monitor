import type { AuthSession } from '@/types/api';
import { getAuthSessionCache, setAuthSessionCache } from './authCache';
import { getCurrentUser } from './monitor';

let pendingAuthSession: Promise<AuthSession> | undefined;

export async function ensureAuthSession(): Promise<AuthSession> {
  const cachedSession = getAuthSessionCache();
  if (cachedSession) {
    return cachedSession;
  }

  if (!pendingAuthSession) {
    pendingAuthSession = getCurrentUser()
      .then((session) => {
        setAuthSessionCache(session);
        return session;
      })
      .finally(() => {
        pendingAuthSession = undefined;
      });
  }

  return pendingAuthSession;
}
