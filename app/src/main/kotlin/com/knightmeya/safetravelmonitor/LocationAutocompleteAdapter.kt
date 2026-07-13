package com.knightmeya.safetravelmonitor

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

class LocationAutocompleteAdapter(context: Context) :
    ArrayAdapter<Address>(context, android.R.layout.simple_dropdown_item_1line), Filterable {

    private var locations: List<Address> = arrayListOf()
    private val geocoder = Geocoder(context)

    override fun getCount(): Int = locations.size

    override fun getItem(position: Int): Address? = locations.getOrNull(position)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(android.R.layout.simple_dropdown_item_1line, parent, false)
        
        val address = getItem(position)
        val textView = view.findViewById<TextView>(android.R.id.text1)
        
        if (address != null) {
            val addressLines = mutableListOf<String>()
            for (i in 0..address.maxAddressLineIndex) {
                val line = address.getAddressLine(i)
                if (!line.isNullOrEmpty()) addressLines.add(line)
            }
            textView.text = addressLines.ifEmpty { listOf(address.featureName ?: "Unknown Location") }.joinToString(", ")
        }
        
        return view
    }

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val results = FilterResults()
                if (constraint != null && (constraint.length >= 3)) {
                    val query = constraint.toString()
                    val apiKey = BuildConfig.PLACES_KEY
                    
                    // Priority 1: Google Places Autocomplete API (if key is valid)
                    if (apiKey.isNotEmpty()) {
                        val places = fetchPlacesFromApi(query)
                        if (places.isNotEmpty()) {
                            results.values = places
                            results.count = places.size
                            return results
                        }
                    }

                    // Priority 2: Fallback to Geocoder
                    try {
                        @Suppress("DEPRECATION")
                        val addresses = geocoder.getFromLocationName(query, 5)
                        if (addresses != null) {
                            results.values = addresses
                            results.count = addresses.size
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("LocationAdapter", "Geocoder error", e)
                    }
                }
                return results
            }

            private fun fetchPlacesFromApi(query: String): List<Address> {
                val list = mutableListOf<Address>()
                val apiKey = BuildConfig.PLACES_KEY
                val urlString = "https://maps.googleapis.com/maps/api/place/autocomplete/json?" +
                        "input=${query.replace(" ", "%20")}" +
                        "&key=$apiKey"
                
                try {
                    val url = URL(urlString)
                    val conn = url.openConnection() as HttpURLConnection
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    val json = JSONObject(response)
                    
                    if (json.getString("status") == "OK") {
                        val predictions = json.getJSONArray("predictions")
                        for (i in 0..<predictions.length()) {
                            val prediction = predictions.getJSONObject(i)
                            val description = prediction.getString("description")
                            val placeId = prediction.getString("place_id")
                            
                            // Convert Place ID to Lat/Lng for use in the app
                            getAddressFromPlaceId(placeId, description)?.let { list.add(it) }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("LocationAdapter", "Places API error", e)
                }
                return list
            }

            private fun getAddressFromPlaceId(placeId: String, description: String): Address? {
                val apiKey = BuildConfig.PLACES_KEY
                val urlString = "https://maps.googleapis.com/maps/api/place/details/json?" +
                        "place_id=$placeId" +
                        "&fields=geometry" +
                        "&key=$apiKey"
                
                try {
                    val url = URL(urlString)
                    val conn = url.openConnection() as HttpURLConnection
                    val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val json = JSONObject(response)
                    
                    if (json.getString("status") == "OK") {
                        val location = json.getJSONObject("result")
                            .getJSONObject("geometry")
                            .getJSONObject("location")
                        
                        val lat = location.getDouble("lat")
                        val lng = location.getDouble("lng")
                        
                        val addr = Address(Locale.getDefault())
                        addr.setAddressLine(0, description)
                        addr.latitude = lat
                        addr.longitude = lng
                        return addr
                    }
                } catch (e: Exception) {
                    android.util.Log.e("LocationAdapter", "Place Details error", e)
                }
                return null
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                if (results != null && results.count > 0) {
                    @Suppress("UNCHECKED_CAST")
                    locations = results.values as List<Address>
                    notifyDataSetChanged()
                } else {
                    notifyDataSetInvalidated()
                }
            }

            override fun convertResultToString(resultValue: Any?): CharSequence {
                val address = resultValue as? Address ?: return ""
                return address.getAddressLine(0) ?: ""
            }
        }
    }
}
