// Firebase Cloud Messaging service worker — handles web push while app is in background.
importScripts('https://www.gstatic.com/firebasejs/10.14.1/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/10.14.1/firebase-messaging-compat.js');

firebase.initializeApp({
  apiKey: 'AIzaSyC9-9ROdtCkb2oxnMViAT_yfHghbtoN9TU',
  authDomain: 'chatflow-e9596.firebaseapp.com',
  projectId: 'chatflow-e9596',
  storageBucket: 'chatflow-e9596.firebasestorage.app',
  messagingSenderId: '1004331287506',
  appId: '1:1004331287506:web:86b5777c3a965d67b57e0b',
});

const messaging = firebase.messaging();

messaging.onBackgroundMessage((payload) => {
  const title = payload.notification?.title ?? 'Gossiplog';
  const body  = payload.notification?.body  ?? '';
  return self.registration.showNotification(title, {
    body,
    icon:  '/icons/Icon-192.png',
    badge: '/icons/Icon-192.png',
    data:  payload.data,
  });
});
