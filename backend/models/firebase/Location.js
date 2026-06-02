const { db } = require('../../config/firebase');
const { v4: uuidv4 } = require('uuid');

class Location {
  static async create(locationData) {
    try {
      const locationId = uuidv4();
      
      const newLocation = {
        ...locationData,
        id: locationId,
        timestamp: new Date().toISOString()
      };

      await db.ref(`locations/${locationId}`).set(newLocation);
      return newLocation;
    } catch (error) {
      console.error('Error creating location:', error);
      throw error;
    }
  }

  static async getByJourney(journeyId) {
    try {
      const snapshot = await db.ref('locations')
        .orderByChild('journey')
        .equalTo(journeyId)
        .once('value');
      
      const locations = snapshot.val() || {};
      return Object.values(locations).sort((a, b) => 
        new Date(b.timestamp) - new Date(a.timestamp)
      );
    } catch (error) {
      console.error('Error getting journey locations:', error);
      throw error;
    }
  }

  static async getLatestByJourney(journeyId) {
    try {
      const locations = await this.getByJourney(journeyId);
      return locations.length > 0 ? locations[0] : null;
    } catch (error) {
      console.error('Error getting latest location:', error);
      throw error;
    }
  }
}

module.exports = Location;
