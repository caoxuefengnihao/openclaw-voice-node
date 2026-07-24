// kws-main.ts -- KWS 页面入口 (v3 新增)
// 跟现有 main.ts 平行,完全不依赖它
// 通过 kws.html 加载,挂载 KwsPage 到 #app

import { createApp } from 'vue'
import KwsPage from './KwsPage.vue'
import './style.css'

createApp(KwsPage).mount('#app')