package bcn.offline.transit

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        val title = TextView(this).apply {
            text = "Offline Transit BCN"
            textSize = 22f
            gravity = Gravity.CENTER
        }
        val tagline = TextView(this).apply {
            text = "Barcelona transit routing, fully offline."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }
        layout.addView(title)
        layout.addView(tagline)
        setContentView(layout)
    }
}
