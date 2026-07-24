import { createApp } from 'vue'
import App from './App.vue'
import './style.css'

// v3 简化:KWS 监听直接集成在 App.vue (主页面),不需要独立 /kws 页面。
// 按老板要求:"kws 功能直接放到 localhost:5174/,跳转都不要"。
createApp(App).mount('#app')
