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
import java.io.IOException

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
                addressLines.add(address.getAddressLine(i))
            }
            textView.text = addressLines.joinToString(", ")
        }
        
        return view
    }

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val results = FilterResults()
                if (constraint != null && constraint.length >= 3) {
                    try {
                        // Geocoder is blocking, but performFiltering runs on a background thread
                        @Suppress("DEPRECATION")
                        val addresses = geocoder.getFromLocationName(constraint.toString(), 5)
                        if (addresses != null) {
                            results.values = addresses
                            results.count = addresses.size
                        }
                    } catch (e: IOException) {
                        android.util.Log.e("LocationAdapter", "Filtering error", e)
                    }
                }
                return results
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
                val addressLines = mutableListOf<String>()
                for (i in 0..address.maxAddressLineIndex) {
                    addressLines.add(address.getAddressLine(i))
                }
                return addressLines.joinToString(", ")
            }
        }
    }
}
