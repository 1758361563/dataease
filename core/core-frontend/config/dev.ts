export function createDevConfig(backend: string) {
  return {
    define: {
      'import.meta.env.VITE_DE_BACKEND_URL': JSON.stringify(backend)
    },
    server: {
      proxy: {
        '/api/f': {
          target: backend,
          changeOrigin: true,
          rewrite: path => path.replace(/^\/api\/f/, '')
        },
        '/api': {
          target: backend,
          changeOrigin: true,
          rewrite: path => path.replace(/^\/api/, 'de2api')
        }
      },
      port: 8080
    }
  }
}
