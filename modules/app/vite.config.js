import { defineConfig } from 'vite'
import scalaJSPlugin from '@scala-js/vite-plugin-scalajs'

export default defineConfig({
  plugins: [
    scalaJSPlugin({
      projectID: 'app',
      cwd: '../..'  // Points to root where build.sbt is located
    })
  ],
  server: {
    port: 5173,
    open: false
  }
})
