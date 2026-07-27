import { history, request } from '@umijs/max';
import type { RequestOptions } from '@umijs/max';
import { message } from 'antd';
import type { ApiResponse } from '@/types/api';
import { clearAuthSessionCache, getAuthSessionVersion } from './authCache';
import { currentRouteWithSearch, loginPathWithRedirect } from '@/utils/router';

const DEFAULT_ERROR_MESSAGE = '请求失败，请稍后重试';
let latestErrorNotice = '';

export async function getApiData<TData>(url: string): Promise<TData> {
  const response = await requestWithAuth<TData>(url);
  return unwrapApiResponse(response);
}

export async function postApiData<TData, TBody extends object>(
  url: string,
  data?: TBody,
): Promise<TData> {
  const response = await requestWithAuth<TData>(url, {
    method: 'POST',
    data,
  });
  return unwrapApiResponse(response);
}

export async function putApiData<TData, TBody extends object>(url: string, data: TBody): Promise<TData> {
  const response = await requestWithAuth<TData>(url, {
    method: 'PUT',
    data,
  });
  return unwrapApiResponse(response);
}

export async function deleteApiData<TData>(url: string): Promise<TData> {
  const response = await requestWithAuth<TData>(url, {
    method: 'DELETE',
  });
  return unwrapApiResponse(response);
}

async function requestWithAuth<TData>(
  url: string,
  options: RequestOptions = {},
): Promise<ApiResponse<TData>> {
  const requestAuthSessionVersion = getAuthSessionVersion();
  try {
    return await request<ApiResponse<TData>>(url, {
      ...options,
      credentials: 'include',
    });
  } catch (error) {
    if (isUnauthorizedError(error)) {
      if (getAuthSessionVersion() === requestAuthSessionVersion) {
        clearAuthSessionCache();
        history.replace(loginPathWithRedirect(currentRouteWithSearch(history.location)));
      }
    } else {
      showErrorNotice(extractErrorMessage(error));
    }
    throw error;
  }
}

function unwrapApiResponse<TData>(response: ApiResponse<TData>): TData {
  if (!response.success) {
    const errorMessage = response.message || DEFAULT_ERROR_MESSAGE;
    showErrorNotice(errorMessage);
    throw new Error(errorMessage);
  }
  return response.data;
}

function isUnauthorizedError(error: unknown): boolean {
  if (!error || typeof error !== 'object') {
    return false;
  }
  const response = 'response' in error ? (error as { response?: { status?: number } }).response : undefined;
  return response?.status === 401;
}

function extractErrorMessage(error: unknown): string {
  if (!error || typeof error !== 'object') {
    return DEFAULT_ERROR_MESSAGE;
  }
  const response = 'response' in error
    ? (error as { response?: { data?: { message?: string } } }).response
    : undefined;
  if (response?.data?.message) {
    return response.data.message;
  }
  if ('message' in error && typeof error.message === 'string' && error.message) {
    return error.message;
  }
  return DEFAULT_ERROR_MESSAGE;
}

function showErrorNotice(errorMessage: string): void {
  if (latestErrorNotice === errorMessage) {
    return;
  }
  latestErrorNotice = errorMessage;
  void message.error(errorMessage, 3, () => {
    if (latestErrorNotice === errorMessage) {
      latestErrorNotice = '';
    }
  });
}
