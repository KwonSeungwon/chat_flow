import { describe, it, expect, beforeEach } from 'vitest'

describe('useTheme', () => {
  beforeEach(() => {
    // Reset DOM
    document.documentElement.removeAttribute('data-bs-theme')
    localStorage.clear()
  })

  it('should default to no stored theme', () => {
    const stored = localStorage.getItem('chatflow-theme')
    expect(stored).toBeNull()
  })

  it('should persist theme to localStorage', () => {
    localStorage.setItem('chatflow-theme', 'dark')
    expect(localStorage.getItem('chatflow-theme')).toBe('dark')
  })

  it('should support light, dark, and auto values', () => {
    const validThemes = ['light', 'dark', 'auto']
    validThemes.forEach(theme => {
      localStorage.setItem('chatflow-theme', theme)
      expect(localStorage.getItem('chatflow-theme')).toBe(theme)
    })
  })

  it('should apply data-bs-theme attribute to document', () => {
    document.documentElement.setAttribute('data-bs-theme', 'dark')
    expect(document.documentElement.getAttribute('data-bs-theme')).toBe('dark')
  })

  it('should remove data-bs-theme attribute on reset', () => {
    document.documentElement.setAttribute('data-bs-theme', 'dark')
    document.documentElement.removeAttribute('data-bs-theme')
    expect(document.documentElement.getAttribute('data-bs-theme')).toBeNull()
  })
})
