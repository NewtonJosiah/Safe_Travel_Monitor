const { db } = require('../../config/firebase');
const { v4: uuidv4 } = require('uuid');

class Notification {
  static async create(notificationData) {
    try {
      const notificationId = uuidv4();
      
      const newNotification = {
        ...notificationData,
        id: notificationId,
        isRead: false,
        createdAt: new Date().toISOString()
      };

      await db.ref(`notifications/${notificationId}`).set(newNotification);
      
      // Also add to user's notification list
      await db.ref(`users/${notificationData.recipient}/notifications/${notificationId}`).set(true);
      
      return newNotification;
    } catch (error) {
      console.error('Error creating notification:', error);
      throw error;
    }
  }

  static async getForUser(userId) {
    try {
      const snapshot = await db.ref(`users/${userId}/notifications`).once('value');
      const notificationIds = snapshot.val() || {};
      
      const notifications = [];
      for (const notificationId of Object.keys(notificationIds)) {
        const notifSnapshot = await db.ref(`notifications/${notificationId}`).once('value');
        const notif = notifSnapshot.val();
        if (notif) {
          notifications.push(notif);
        }
      }
      
      return notifications.sort((a, b) => 
        new Date(b.createdAt) - new Date(a.createdAt)
      );
    } catch (error) {
      console.error('Error getting notifications for user:', error);
      throw error;
    }
  }

  static async markAsRead(notificationId) {
    try {
      await db.ref(`notifications/${notificationId}`).update({ 
        isRead: true, 
        readAt: new Date().toISOString() 
      });
    } catch (error) {
      console.error('Error marking notification as read:', error);
      throw error;
    }
  }

  static async delete(notificationId) {
    try {
      const notification = await db.ref(`notifications/${notificationId}`).once('value');
      const notifData = notification.val();
      
      if (notifData) {
        await db.ref(`users/${notifData.recipient}/notifications/${notificationId}`).remove();
      }
      
      await db.ref(`notifications/${notificationId}`).remove();
    } catch (error) {
      console.error('Error deleting notification:', error);
      throw error;
    }
  }
}

module.exports = Notification;
