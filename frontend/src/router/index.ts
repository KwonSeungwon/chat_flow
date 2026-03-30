import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '@/views/ChatView.vue'
import SearchView from '@/views/SearchView.vue'
import WidgetView from '@/views/WidgetView.vue'
import LoginView from '@/views/LoginView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      redirect: '/chat'
    },
    {
      path: '/login',
      name: 'login',
      component: LoginView,
      meta: { public: true }
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
      component: WidgetView,
      meta: { public: true }
    }
  ]
})

router.beforeEach((to) => {
  if (to.meta.public) return true
  const token = localStorage.getItem('chatflow-token')
  if (!token) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
