package lk.coopfed.knoweb.till

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import lk.coopfed.knoweb.till.ui.HelloScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            HelloScreen()
        }
    }
}