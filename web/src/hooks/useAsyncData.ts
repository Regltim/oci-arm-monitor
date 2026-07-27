import { useCallback, useEffect, useState, type DependencyList } from 'react';

interface UseAsyncDataOptions {
  enabled?: boolean;
}

interface RefreshOptions {
  silent?: boolean;
}

interface UseAsyncDataResult<TData> {
  data?: TData;
  loading: boolean;
  error?: Error;
  refresh: (refreshOptions?: RefreshOptions) => Promise<void>;
}

export function useAsyncData<TData>(
  loader: () => Promise<TData>,
  deps: DependencyList = [],
  options: UseAsyncDataOptions = {},
): UseAsyncDataResult<TData> {
  const enabled = options.enabled ?? true;
  const [data, setData] = useState<TData>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error>();

  const refresh = useCallback(async (refreshOptions: RefreshOptions = {}) => {
    if (!enabled) {
      return;
    }
    if (!refreshOptions.silent) {
      setLoading(true);
    }
    setError(undefined);
    try {
      const nextData = await loader();
      setData(nextData);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError : new Error('请求失败'));
    } finally {
      if (!refreshOptions.silent) {
        setLoading(false);
      }
    }
  }, [enabled, ...deps]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return {
    data,
    loading,
    error,
    refresh,
  };
}
