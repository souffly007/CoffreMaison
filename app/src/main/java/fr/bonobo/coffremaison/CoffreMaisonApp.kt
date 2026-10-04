package fr.bonobo.coffremaison
import android.app.Application
import androidx.room.Room
import fr.bonobo.coffremaison.data.AppDatabase
class CoffreMaisonApp:Application(){ val db by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "coffre_maison.db").fallbackToDestructiveMigration().build() } }
