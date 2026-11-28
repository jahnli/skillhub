import { describe, expect, it } from 'vitest'
import * as localAuth from './use-local-auth'

describe('use-local-auth module exports', () => {
  it('exports useLocalLogin hook', () => {
    expect(localAuth.useLocalLogin).toBeTypeOf('function')
  })
})
