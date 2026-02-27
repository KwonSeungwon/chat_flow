import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '@/views/ChatView.vue'
import SearchView from '@/views/SearchView.vue'
import WidgetView from '@/views/WidgetView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      redirect: '/chat'
    },
    {
      path: '/chat/:roomId?',
      name: 'chat',
      component: ChatView,
      props: true
    },
    {
      path: '/search',
      name: 'search',
      component: SearchView
    },
    {
      path: '/widget',
      name: 'widget',
      component: WidgetView
    }
  ]
})

export default router