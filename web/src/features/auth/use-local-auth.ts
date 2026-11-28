import { useMutation, useQueryClient } from '@tanstack/react-query'
import { authApi } from '@/api/client'
import type { LocalLoginRequest, User } from '@/api/types'
import { clearSessionScopedQueries } from '@/features/notification/notification-session'

/**
 * Local-account auth mutations for classic username-password login.
 */
export function useLocalLogin() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (request: LocalLoginRequest) => authApi.localLogin(request),
    onSuccess: (user) => {
      clearSessionScopedQueries(queryClient)
      queryClient.setQueryData<User | null>(['auth', 'me'], user)
    },
  })
}
