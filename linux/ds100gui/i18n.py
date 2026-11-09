"""UI texts in Slovak and English (index 0 / 1)."""
import locale
import os

LANGS = {"sk": "Slovenčina", "en": "English"}

T = {
    "app": ("DS100 Datalogger", "DS100 Datalogger"),
    "menu": ("Menu", "Menu"),
    "setup": ("Nastavenie", "Setup"),
    "history": ("História", "History"),
    "graph": ("Graf", "Graph"),
    "exit": ("Koniec", "Exit"),
    "management": ("Správa", "Management"),
    "language": ("Jazyk", "Language"),
    "help": ("Pomoc", "Help"),
    "about": ("O programe", "About DS100 Datalogger"),
    "devinfo": ("Informácie o zariadení", "Device information"),
    "dev_id": ("ID zariadenia", "Device ID"),
    "title": ("Názov", "Title"),
    "update_time": ("Posledné stiahnutie", "Last download"),
    "record_time": ("Posledný záznam", "Last record"),
    "temperature": ("Teplota", "Temperature"),
    "humidity": ("Vlhkosť", "Humidity"),
    "dewpoint": ("Rosný bod", "Dew point"),
    "interval": ("Interval", "Interval"),
    "total": ("Počet záznamov", "Total records"),
    "maxmin": ("Max/Min", "Max/Min"),
    "max_t": ("Max teplota", "Max temperature"),
    "min_t": ("Min teplota", "Min temperature"),
    "max_h": ("Max vlhkosť", "Max humidity"),
    "min_h": ("Min vlhkosť", "Min humidity"),
    "today": ("Dnes", "Today"),
    "yesterday": ("Včera", "Yesterday"),
    "connect": ("Načítať zo zariadenia", "Read from device"),
    "sync": ("Synchronizovať čas", "Sync time"),
    "connected": ("Pripojené", "Connected"),
    "unconnected": ("Nepripojené", "Disconnected"),
    "start": ("Od", "From"),
    "end": ("Do", "To"),
    "search": ("Hľadať", "Search"),
    "reset": ("Reset", "Reset"),
    "type": ("Typ", "Type"),
    "number": ("Číslo", "Number"),
    "time": ("Čas", "Time"),
    "export_csv": ("Export do CSV", "Export to CSV"),
    "temp_unit": ("Jednotka teploty", "Temperature unit"),
    "date_fmt": ("Formát dátumu", "Date format"),
    "display": ("Displej", "Display"),
    "minute": ("minút", "minutes"),
    "save": ("Uložiť", "Save"),
    "cancel": ("Zrušiť", "Cancel"),
    "del_dev": ("Odstrániť zariadenie z databázy", "Remove device from database"),
    "del_hist": ("Vymazať históriu v databáze", "Delete history in database"),
    "clear_dev": ("Vymazať pamäť zariadenia", "Erase device memory"),
    "notice": ("Upozornenie", "Notice"),
    "reading": ("Čítam…", "Reading…"),
    "new_records": ("Počet nových záznamov", "New records"),
    "q_clear": ("Naozaj vymazať históriu v zariadení?", "Really erase the history stored in the device?"),
    "cleared": ("Vymazané. Zariadenie odpoj a znova zapoj.", "Erased. Unplug the device and plug it in again."),
    "q_del_dev": ("Naozaj odstrániť zariadenie %s z databázy?", "Really remove device %s from the database?"),
    "q_del_hist": ("Naozaj vymazať históriu zariadenia %s?", "Really delete the history of device %s?"),
    "deleted": ("Odstránené z databázy.", "Removed from the database."),
    "exported": ("Export hotový.", "Export finished."),
    "sync_ok": ("Čas zariadenia nastavený.", "Device time synchronized."),
    "no_device": ("Zariadenie nie je pripojené.", "Device is not connected."),
    "no_data": ("Žiadne dáta", "No data"),
    "all": ("Teplota + vlhkosť", "Temperature + humidity"),
    "simulator": ("simulátor", "simulator"),
    "sync_wait": ("Čakám, kým logger prevezme čas… %d s (najviac 3 min)",
                  "Waiting for the logger to take the time… %d s (max. 3 min)"),
    "clock_diff": ("Hodiny loggera sa od počítača líšia o %+.0f s.",
                   "Logger clock differs from the PC by %+.0f s."),
    "export_graph": ("Exportovať (PNG, PDF, SVG, CSV)…", "Export (PNG, PDF, SVG, CSV)…"),
    "time_axis": ("Dátum a čas", "Date and time"),
    "database": ("Databáza v počítači", "Database on this computer"),
    "syncing": ("Nastavujem čas zariadenia…", "Synchronizing device time…"),
    "clearing": ("Mažem pamäť zariadenia…", "Erasing device memory…"),
    "writing": ("Zapisujem nastavenia…", "Writing settings…"),
    "saved": ("Nastavenia boli zapísané do zariadenia.", "Settings were written to the device."),
}

_lang = 1


def default_lang():
    """Slovak if the system language is Slovak, otherwise English."""
    for var in ("LC_ALL", "LC_MESSAGES", "LANG", "LANGUAGE"):
        v = os.environ.get(var)
        if v:
            return "sk" if v.lower().startswith("sk") else "en"
    loc = locale.getlocale()[0] or ""
    return "sk" if loc.lower().startswith("sk") else "en"


def set_lang(code):
    global _lang
    _lang = 1 if code == "en" else 0


def tr(key):
    v = T.get(key)
    return v[_lang] if v else key
