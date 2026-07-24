import { createApp } from 'vue'
import App from './App.vue'
import KwsPage from './KwsPage.vue'
import './style.css'

// 根据路径分发页面:
//   /         -> App.vue (原有 v2 语音对话页面)
//   /kws      -> KwsPage.vue (v3 KWS 唤醒页面)
// Vite dev server 默认 SPA fallback,/kws 会回到 index.html。
const Page = location.pathname === '/kws' ? KwsPage : App
createApp(Page).mount('#app')
