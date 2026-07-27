const DEFAULT_AUTH_REDIRECT = '/dashboard';

interface RouteLocationLike {
  pathname: string;
  search: string;
}

export function currentRouteWithSearch(location: RouteLocationLike): string {
  return `${location.pathname || DEFAULT_AUTH_REDIRECT}${location.search || ''}`;
}

export function loginPathWithRedirect(redirectPath: string): string {
  return `/login?redirect=${encodeURIComponent(normalizeInternalPath(redirectPath))}`;
}

export function resolveLoginRedirect(search: string): string {
  const redirect = new URLSearchParams(search).get('redirect');
  if (!redirect) {
    return DEFAULT_AUTH_REDIRECT;
  }
  return normalizeInternalPath(redirect);
}

function normalizeInternalPath(value: string): string {
  if (!value.startsWith('/') || value.startsWith('//')) {
    return DEFAULT_AUTH_REDIRECT;
  }
  if (value.startsWith('/login')) {
    return DEFAULT_AUTH_REDIRECT;
  }
  return value;
}
