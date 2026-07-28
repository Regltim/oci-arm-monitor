import type { ApiResponse, PublicReportView } from '@/types/api';
import { request } from '@umijs/max';

export async function getPublicReport(
  snapshotId: string,
  accessToken: string,
  signal?: AbortSignal,
): Promise<PublicReportView> {
  const response = await request<ApiResponse<PublicReportView>>(
    `/api/public/reports/${encodeURIComponent(snapshotId)}`,
    {
      method: 'GET',
      credentials: 'omit',
      headers: {
        Authorization: `Bearer ${accessToken}`,
      },
      signal,
    },
  );
  if (!response.success || !response.data) {
    throw new Error('报告不存在或已过期');
  }
  return response.data;
}
