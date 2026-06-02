const { db } = require('../../config/firebase');
const bcrypt = require('bcryptjs');

class User {
  static async create(userData) {
    try {
      const userId = db.ref('users').push().key;
      const hashedPassword = await bcrypt.hash(userData.password, 10);
      
      const newUser = {
        ...userData,
        password: hashedPassword,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString()
      };

      await db.ref(`users/${userId}`).set(newUser);
      return { id: userId, ...newUser };
    } catch (error) {
      console.error('Error creating user:', error);
      throw error;
    }
  }

  static async findByEmail(email) {
    try {
      const snapshot = await db.ref('users').orderByChild('email').equalTo(email).once('value');
      const users = snapshot.val();
      
      if (!users) return null;
      
      const userId = Object.keys(users)[0];
      return { id: userId, ...users[userId] };
    } catch (error) {
      console.error('Error finding user by email:', error);
      throw error;
    }
  }

  static async findById(userId) {
    try {
      const snapshot = await db.ref(`users/${userId}`).once('value');
      const user = snapshot.val();
      
      if (!user) return null;
      return { id: userId, ...user };
    } catch (error) {
      console.error('Error finding user by ID:', error);
      throw error;
    }
  }

  static async update(userId, updates) {
    try {
      updates.updatedAt = new Date().toISOString();
      await db.ref(`users/${userId}`).update(updates);
      const updated = await this.findById(userId);
      return updated;
    } catch (error) {
      console.error('Error updating user:', error);
      throw error;
    }
  }

  static async delete(userId) {
    try {
      await db.ref(`users/${userId}`).remove();
    } catch (error) {
      console.error('Error deleting user:', error);
      throw error;
    }
  }

  static async addFriend(userId, friendId) {
    try {
      await db.ref(`users/${userId}/friends/${friendId}`).set(true);
    } catch (error) {
      console.error('Error adding friend:', error);
      throw error;
    }
  }

  static async removeFriend(userId, friendId) {
    try {
      await db.ref(`users/${userId}/friends/${friendId}`).remove();
    } catch (error) {
      console.error('Error removing friend:', error);
      throw error;
    }
  }
}

module.exports = User;
