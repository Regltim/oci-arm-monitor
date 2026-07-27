import { useAsyncData } from '@/hooks/useAsyncData';
import { ensureAuthSession } from '@/services/authSession';
import { currentRouteWithSearch, loginPathWithRedirect } from '@/utils/router';
import { history, Outlet, useLocation } from '@umijs/max';
import { Spin } from 'antd';
import type { ReactNode } from 'react';
import { useEffect } from 'react';

interface AuthWrapperProps {
  children: ReactNode;
}

export default function AuthWrapper({ children }: AuthWrapperProps) {
  const location = useLocation();
  const { data, loading, error } = useAsyncData(ensureAuthSession);

  useEffect(() => {
    if (!loading && error) {
      history.replace(loginPathWithRedirect(currentRouteWithSearch(location)));
    }
  }, [error, loading, location.pathname, location.search]);

  if (loading || (!data && !error)) {
    return (
      <div className="auth-loading">
        <Spin />
      </div>
    );
  }

  if (error) {
    return null;
  }

  return children ? <>{children}</> : <Outlet />;
}
