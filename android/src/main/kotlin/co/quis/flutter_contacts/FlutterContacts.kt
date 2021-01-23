package co.quis.flutter_contacts
import kotlin.text.*
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import co.quis.flutter_contacts.properties.Account as PAccount
import co.quis.flutter_contacts.properties.Address as PAddress
import co.quis.flutter_contacts.properties.Email as PEmail
import co.quis.flutter_contacts.properties.Event as PEvent
import co.quis.flutter_contacts.properties.Name as PName
import co.quis.flutter_contacts.properties.Note as PNote
import co.quis.flutter_contacts.properties.Organization as POrganization
import co.quis.flutter_contacts.properties.Phone as PPhone
import co.quis.flutter_contacts.properties.SocialMedia as PSocialMedia
import co.quis.flutter_contacts.properties.Website as PWebsite

class FlutterContacts {
    companion object {
        fun get(
            resolver: ContentResolver,
            id: String?,
            withDetails: Boolean,
            withPhotos: Boolean,
            useHighResolutionPhotos: Boolean,
            idIsRawContactId: Boolean = false
        ): List<Map<String, Any?>> {
            if (id == null && !withDetails && !withPhotos) {
                return getQuick(resolver)
            }

            // All fields we care about – ID and display name are always included
            var projection = mutableListOf(
                Data.CONTACT_ID,
                Data.MIMETYPE,
                Contacts.DISPLAY_NAME_PRIMARY
            )
            if (withPhotos) {
                projection.add(Photo.PHOTO)
            }
            if (withDetails) {
                projection.addAll(
                    listOf(
                        StructuredName.PREFIX,
                        StructuredName.GIVEN_NAME,
                        StructuredName.MIDDLE_NAME,
                        StructuredName.FAMILY_NAME,
                        StructuredName.SUFFIX,
                        Nickname.NAME,
                        StructuredName.PHONETIC_GIVEN_NAME,
                        StructuredName.PHONETIC_FAMILY_NAME,
                        StructuredName.PHONETIC_MIDDLE_NAME,
                        Phone.NUMBER,
                        Phone.NORMALIZED_NUMBER,
                        Phone.TYPE,
                        Phone.LABEL,
                        Phone.IS_PRIMARY,
                        Email.ADDRESS,
                        Email.TYPE,
                        Email.LABEL,
                        Email.IS_PRIMARY,
                        StructuredPostal.FORMATTED_ADDRESS,
                        StructuredPostal.STREET,
                        StructuredPostal.POBOX,
                        StructuredPostal.NEIGHBORHOOD,
                        StructuredPostal.CITY,
                        StructuredPostal.REGION,
                        StructuredPostal.POSTCODE,
                        StructuredPostal.COUNTRY,
                        StructuredPostal.TYPE,
                        StructuredPostal.LABEL,
                        Organization.COMPANY,
                        Organization.TITLE,
                        Organization.DEPARTMENT,
                        Organization.JOB_DESCRIPTION,
                        Organization.SYMBOL,
                        Organization.PHONETIC_NAME,
                        Organization.OFFICE_LOCATION,
                        Website.URL,
                        Website.TYPE,
                        Website.LABEL,
                        Im.DATA,
                        Im.PROTOCOL,
                        Im.CUSTOM_PROTOCOL,
                        Event.START_DATE,
                        Event.TYPE,
                        Event.LABEL,
                        Note.NOTE,
                        Data.RAW_CONTACT_ID,
                        RawContacts.ACCOUNT_TYPE,
                        RawContacts.ACCOUNT_NAME
                    )
                )
            }

            // This drops contacts not part of any group
            // See: https://stackoverflow.com/questions/28665587/what-does-contactscontract-contacts-in-visible-group-mean-in-android
            var selection = "${Data.IN_VISIBLE_GROUP} = 1"
            var selectionArgs = arrayOf<String>()

            if (id != null) {
                if (idIsRawContactId) {
                    selection += " AND ${Data.RAW_CONTACT_ID} = ?"
                } else {
                    selection += " AND ${Data.CONTACT_ID} = ?"
                }
                selectionArgs = arrayOf(id)
            }

            // NOTE: The projection filters columns, and the selection filters rows. We
            // could filter rows to those with requested MIME types, but it introduces a
            // WHERE in the query which seems to double its execution time, so we
            // instead loop through all rows and filter them in Kotlin.

            // Query contact database
            val cursor = resolver.query(
                Data.CONTENT_URI,
                projection.toTypedArray(),
                selection,
                selectionArgs,
                /*sortOrder=*/null
            )

            // List of all contacts
            var contacts = mutableListOf<Contact>()
            if (cursor == null) {
                return listOf()
            }

            // Maps contact ID to its index in `contacts`
            var index = mutableMapOf<String, Int>()

            fun getString(col: String): String = cursor.getString(cursor.getColumnIndex(col)) ?: ""
            fun getInt(col: String): Int = cursor.getInt(cursor.getColumnIndex(col)) ?: 0

            while (cursor.moveToNext()) {
                // ID and display name
                val id = getString(Data.CONTACT_ID)
                if (id !in index) {
                    var contact = Contact(
                        /*id=*/id,
                        /*displayName=*/getString(Contacts.DISPLAY_NAME_PRIMARY)
                    )

                    // Fetch high-resolution photo if requested
                    if (withPhotos && useHighResolutionPhotos) {
                        val contactUri: Uri =
                            ContentUris.withAppendedId(Contacts.CONTENT_URI, id.toLong())
                        val displayPhotoUri: Uri =
                            Uri.withAppendedPath(contactUri, Contacts.Photo.DISPLAY_PHOTO)
                        try {
                            var fis: InputStream? = resolver.openInputStream(displayPhotoUri)
                            contact.photo = fis?.readBytes()
                        } catch (e: FileNotFoundException) {
                            // This happens when no high-resolution photo exists, and is
                            // a common situation
                        }
                    }

                    index[id] = contacts.size
                    contacts.add(contact)
                }
                var contact: Contact = contacts[index[id]!!]

                // The MIME type of the data in current row (e.g. phone, email, etc)
                val mimetype = getString(Data.MIMETYPE)

                // Photos
                if (withPhotos && mimetype == Photo.CONTENT_ITEM_TYPE) {
                    // Don't override high-resolution photo if there is one
                    if (!useHighResolutionPhotos || contact.photo == null) {
                        contact.photo =
                            cursor.getBlob(cursor.getColumnIndex(Photo.PHOTO))
                    }
                }

                // Other fields
                if (withDetails) {
                    // Raw IDs are IDs of the contact in different accounts (e.g. the
                    // same contact might have Google, WhatsApp and Skype accounts, each
                    // with its own raw ID)
                    val rawId = getString(Data.RAW_CONTACT_ID)
                    val accountType = getString(RawContacts.ACCOUNT_TYPE)
                    val accountName = getString(RawContacts.ACCOUNT_NAME)
                    var accountSeen = false
                    for (account in contact.accounts) {
                        if (account.rawId == rawId) {
                            accountSeen = true
                            account.mimetypes =
                                (account.mimetypes + mimetype).toSortedSet().toList()
                        }
                    }
                    if (!accountSeen) {
                        val account = PAccount(
                            rawId,
                            accountType,
                            accountName,
                            listOf(mimetype)
                        )
                        contact.accounts += account
                    }

                    when (mimetype) {
                        StructuredName.CONTENT_ITEM_TYPE -> {
                            // save nickname in case it was there already
                            val nickname: String = contact.name.nickname
                            contact.name = PName(
                                getString(StructuredName.GIVEN_NAME),
                                getString(StructuredName.FAMILY_NAME),
                                getString(StructuredName.MIDDLE_NAME),
                                getString(StructuredName.PREFIX),
                                getString(StructuredName.SUFFIX),
                                nickname,
                                getString(StructuredName.PHONETIC_GIVEN_NAME),
                                getString(StructuredName.PHONETIC_FAMILY_NAME),
                                getString(StructuredName.PHONETIC_MIDDLE_NAME)
                            )
                        }
                        Nickname.CONTENT_ITEM_TYPE ->
                            contact.name.nickname = getString(Nickname.NAME)
                        Phone.CONTENT_ITEM_TYPE -> {
                            val label: String = getPhoneLabel(cursor)
                            val customLabel: String =
                                if (label == "custom") getPhoneCustomLabel(cursor) else ""
                            val phone = PPhone(
                                getString(Phone.NUMBER),
                                getString(Phone.NORMALIZED_NUMBER),
                                label,
                                customLabel,
                                getInt(Phone.IS_PRIMARY) == 1
                            )
                            contact.phones += phone
                        }
                        Email.CONTENT_ITEM_TYPE -> {
                            val label: String = getEmailLabel(cursor)
                            val customLabel: String =
                                if (label == "custom") getEmailCustomLabel(cursor) else ""
                            val email = PEmail(
                                getString(Email.ADDRESS),
                                label,
                                customLabel,
                                getInt(Email.IS_PRIMARY) == 1
                            )
                            contact.emails += email
                        }
                        StructuredPostal.CONTENT_ITEM_TYPE -> {
                            val label: String = getAddressLabel(cursor)
                            val customLabel: String =
                                if (label == "custom") getAddressCustomLabel(cursor) else ""
                            val address = PAddress(
                                getString(StructuredPostal.FORMATTED_ADDRESS),
                                label,
                                customLabel,
                                getString(StructuredPostal.STREET),
                                getString(StructuredPostal.POBOX),
                                getString(StructuredPostal.NEIGHBORHOOD),
                                getString(StructuredPostal.CITY),
                                getString(StructuredPostal.REGION),
                                getString(StructuredPostal.POSTCODE),
                                getString(StructuredPostal.COUNTRY),
                                "",
                                "",
                                ""
                            )
                            contact.addresses += address
                        }
                        Organization.CONTENT_ITEM_TYPE -> {
                            val organization = POrganization(
                                getString(Organization.COMPANY),
                                getString(Organization.TITLE),
                                getString(Organization.DEPARTMENT),
                                getString(Organization.JOB_DESCRIPTION),
                                getString(Organization.SYMBOL),
                                getString(Organization.PHONETIC_NAME),
                                getString(Organization.OFFICE_LOCATION)
                            )
                            contact.organizations += organization
                        }
                        Website.CONTENT_ITEM_TYPE -> {
                            val label: String = getWebsiteLabel(cursor)
                            val customLabel: String =
                                if (label == "custom") getWebsiteCustomLabel(cursor) else ""
                            val website = PWebsite(
                                getString(Website.URL),
                                label,
                                customLabel
                            )
                            contact.websites += website
                        }
                        Im.CONTENT_ITEM_TYPE -> {
                            val label: String = getSocialMediaLabel(cursor)
                            val customLabel: String =
                                if (label == "custom") getSocialMediaCustomLabel(cursor) else ""
                            val socialMedia = PSocialMedia(
                                getString(Im.DATA),
                                label,
                                customLabel
                            )
                            contact.socialMedias += socialMedia
                        }
                        Event.CONTENT_ITEM_TYPE -> {
                            val label: String = getEventLabel(cursor)
                            val customLabel: String =
                                if (label == "custom") getEventCustomLabel(cursor) else ""
                            val event = PEvent(
                                getString(Event.START_DATE),
                                label,
                                customLabel,
                                false
                            )
                            contact.events += event
                        }
                        Note.CONTENT_ITEM_TYPE -> {
                            val note: String = getString(Note.NOTE)
                            // It seems that every contact has an empty note by default;
                            // filter empty notes to avoid confusion
                            if (!note.isEmpty()) {
                                val note = PNote(getString(Note.NOTE))
                                contact.notes += note
                            }
                        }
                    }
                }
            }

            cursor.close()

            return contacts.map { it.toMap() }
        }

        fun new(
            resolver: ContentResolver,
            contactMap: Map<String, Any?>
        ): Map<String, Any?>? {
            val ops = mutableListOf<ContentProviderOperation>()

            val contact = Contact.fromMap(contactMap)

            // If no account is provided, create with no account type or account name.
            //
            // On Android, it is possible the default Contacts app will synchronize it
            // with Gmail and add `com.google` account types, seconds after creation, if
            // the option is enabled. Other apps may do the same if they have a sync
            // option enabled.
            //
            // If an account is provided, use it explicitly instead.
            if (contact.accounts.isEmpty()) {
                ops.add(
                    ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                        .withValue(RawContacts.ACCOUNT_TYPE, null)
                        .withValue(RawContacts.ACCOUNT_NAME, null)
                        .build()
                )
            } else {
                ops.add(
                    ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                        .withValue(RawContacts.ACCOUNT_TYPE, contact.accounts.first().type)
                        .withValue(RawContacts.ACCOUNT_NAME, contact.accounts.first().name)
                        .build()
                )
            }

            // Build all properties
            buildOpsForContact(contact, ops)

            // Save
            val addContactResults =
                resolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
            val rawId: Long = ContentUris.parseId(addContactResults[0].uri!!)

            // Add avatar if provided (needs to be after saving the contact so we know
            // its raw contact ID)
            if (contact.photo != null) {
                buildOpsForPhoto(resolver, contact.photo!!, ops, rawId)
            }

            // Load contacts with that raw ID, which will give us the full contact as it
            // was saved
            val newContacts: List<Map<String, Any?>> = get(
                resolver,
                rawId.toString(), /*with_details=*/ true, /*with_photos=*/true,
                /*useHighResolutionPhotos=*/true, /*idIsRawContactId=*/true
            )

            if (newContacts.isEmpty()) {
                return null
            }
            return newContacts[0]
        }

        fun update(
            resolver: ContentResolver,
            contactMap: Map<String, Any?>,
            deletePhoto: Boolean
        ): String? {
            val ops = mutableListOf<ContentProviderOperation>()

            val contact = Contact.fromMap(contactMap)

            // We'll use the first raw contact ID for adds. There might a better way to
            // do this...
            if (contact.accounts.isEmpty()) {
                return "cannot update contact without raw contact ID"
            }
            val contactId = contact.id
            val rawContactId = contact.accounts.first().rawId

            // Update name and other properties, by deleting existing ones and creating
            // new ones
            ops.add(
                ContentProviderOperation.newDelete(Data.CONTENT_URI)
                    .withSelection(
                        "${RawContacts.CONTACT_ID}=? and ${Data.MIMETYPE} in (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            contactId,
                            StructuredName.CONTENT_ITEM_TYPE,
                            Nickname.CONTENT_ITEM_TYPE,
                            Phone.CONTENT_ITEM_TYPE,
                            Email.CONTENT_ITEM_TYPE,
                            StructuredPostal.CONTENT_ITEM_TYPE,
                            Organization.CONTENT_ITEM_TYPE,
                            Website.CONTENT_ITEM_TYPE,
                            Im.CONTENT_ITEM_TYPE,
                            Event.CONTENT_ITEM_TYPE,
                            Note.CONTENT_ITEM_TYPE
                        )
                    )
                    .build()
            )
            if (deletePhoto) {
                ops.add(
                    ContentProviderOperation.newDelete(Data.CONTENT_URI)
                        .withSelection(
                            "${RawContacts.CONTACT_ID}=? and ${Data.MIMETYPE}=?",
                            arrayOf(
                                contactId,
                                Photo.CONTENT_ITEM_TYPE
                            )
                        )
                        .build()
                )
            }
            buildOpsForContact(contact, ops, rawContactId)
            if (contact.photo != null) {
                buildOpsForPhoto(resolver, contact.photo!!, ops, rawContactId.toLong())
            }

            // Save
            

            try {
                resolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
            }
            catch (e: Exception) {
                }
            
           

            return null
        }

        fun delete(resolver: ContentResolver, contactIds: List<String>) {
            val ops = mutableListOf<ContentProviderOperation>()

            for (contactId in contactIds) {
                ops.add(
                    ContentProviderOperation.newDelete(RawContacts.CONTENT_URI)
                        .withSelection("${RawContacts.CONTACT_ID}=?", arrayOf(contactId))
                        .build()
                )
            }

            resolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
        }

        // getQuick is like get(id = null, withDetails = false, withPhotos = false) but
        // much faster (100 ms vs 400 ms on a Pixel 3 with 600 contacts)
        private fun getQuick(resolver: ContentResolver): List<Map<String, Any?>> {
            // This drops contacts not part of any group
            // See: https://stackoverflow.com/questions/28665587/what-does-contactscontract-contacts-in-visible-group-mean-in-android
            val selection = "${Data.IN_VISIBLE_GROUP} = 1"

            // Query contact database
            val cursor = resolver.query(
                Contacts.CONTENT_URI,
                /*projection=*/null,
                selection,
                /*selectionArgs=*/null,
                /*sortOrder=*/null
            )

            // List of all contacts
            var contacts = mutableListOf<Contact>()
            if (cursor == null) {
                return listOf()
            }

            while (cursor.moveToNext()) {
                contacts.add(
                    Contact(
                        /*id=*/(cursor.getString(cursor.getColumnIndex(Contacts._ID)) ?: ""),
                        /*displayName=*/(cursor.getString(cursor.getColumnIndex(Contacts.DISPLAY_NAME_PRIMARY)) ?: "")
                    )
                )
            }

            cursor.close()
            return contacts.map { it.toMap() }
        }

        private fun getPhoneLabel(cursor: Cursor): String {
            val type = cursor.getInt(cursor.getColumnIndex(Phone.TYPE))
            return when (type) {
                Phone.TYPE_ASSISTANT -> "assistant"
                Phone.TYPE_CALLBACK -> "callback"
                Phone.TYPE_CAR -> "car"
                Phone.TYPE_COMPANY_MAIN -> "companyMain"
                Phone.TYPE_FAX_HOME -> "faxHome"
                Phone.TYPE_FAX_WORK -> "faxWork"
                Phone.TYPE_HOME -> "home"
                Phone.TYPE_ISDN -> "isdn"
                Phone.TYPE_MAIN -> "main"
                Phone.TYPE_MMS -> "mms"
                Phone.TYPE_MOBILE -> "mobile"
                Phone.TYPE_OTHER -> "other"
                Phone.TYPE_OTHER_FAX -> "faxOther"
                Phone.TYPE_PAGER -> "pager"
                Phone.TYPE_RADIO -> "radio"
                Phone.TYPE_TELEX -> "telex"
                Phone.TYPE_TTY_TDD -> "ttyTtd"
                Phone.TYPE_WORK -> "work"
                Phone.TYPE_WORK_MOBILE -> "workMobile"
                Phone.TYPE_WORK_PAGER -> "workPager"
                Phone.TYPE_CUSTOM -> "custom"
                else -> "mobile"
            }
        }

        private fun getPhoneCustomLabel(cursor: Cursor): String {
            return cursor.getString(cursor.getColumnIndex(Phone.LABEL)) ?: ""
        }

        private data class PhoneLabelPair(val label: Int, val customLabel: String)
        private fun getPhoneLabelInv(label: String, customLabel: String): PhoneLabelPair {
            return when (label) {
                "assistant" -> PhoneLabelPair(Phone.TYPE_ASSISTANT, "")
                "callback" -> PhoneLabelPair(Phone.TYPE_CALLBACK, "")
                "car" -> PhoneLabelPair(Phone.TYPE_CAR, "")
                "companyMain" -> PhoneLabelPair(Phone.TYPE_COMPANY_MAIN, "")
                "faxHome" -> PhoneLabelPair(Phone.TYPE_FAX_HOME, "")
                "faxOther" -> PhoneLabelPair(Phone.TYPE_OTHER_FAX, "")
                "faxWork" -> PhoneLabelPair(Phone.TYPE_FAX_WORK, "")
                "home" -> PhoneLabelPair(Phone.TYPE_HOME, "")
                "isdn" -> PhoneLabelPair(Phone.TYPE_ISDN, "")
                "main" -> PhoneLabelPair(Phone.TYPE_MAIN, "")
                "mms" -> PhoneLabelPair(Phone.TYPE_MMS, "")
                "mobile" -> PhoneLabelPair(Phone.TYPE_MOBILE, "")
                "other" -> PhoneLabelPair(Phone.TYPE_OTHER, "")
                "pager" -> PhoneLabelPair(Phone.TYPE_PAGER, "")
                "radio" -> PhoneLabelPair(Phone.TYPE_RADIO, "")
                "telex" -> PhoneLabelPair(Phone.TYPE_TELEX, "")
                "ttyTtd" -> PhoneLabelPair(Phone.TYPE_TTY_TDD, "")
                "work" -> PhoneLabelPair(Phone.TYPE_WORK, "")
                "workMobile" -> PhoneLabelPair(Phone.TYPE_WORK_MOBILE, "")
                "workPager" -> PhoneLabelPair(Phone.TYPE_WORK_PAGER, "")
                "custom" -> PhoneLabelPair(Phone.TYPE_CUSTOM, customLabel)
                else -> PhoneLabelPair(Phone.TYPE_CUSTOM, label)
            }
        }

        private fun getEmailLabel(cursor: Cursor): String {
            val type = cursor.getInt(cursor.getColumnIndex(Email.TYPE))
            return when (type) {
                Email.TYPE_CUSTOM -> "custom"
                Email.TYPE_HOME -> "home"
                Email.TYPE_MOBILE -> "mobile"
                Email.TYPE_OTHER -> "other"
                Email.TYPE_WORK -> "work"
                else -> "home"
            }
        }

        private fun getEmailCustomLabel(cursor: Cursor): String {
            return cursor.getString(cursor.getColumnIndex(Email.LABEL)) ?: ""
        }

        private data class EmailLabelPair(val label: Int, val customLabel: String)
        private fun getEmailLabelInv(label: String, customLabel: String): EmailLabelPair {
            return when (label) {
                "home" -> EmailLabelPair(Email.TYPE_HOME, "")
                "mobile" -> EmailLabelPair(Email.TYPE_MOBILE, "")
                "other" -> EmailLabelPair(Email.TYPE_OTHER, "")
                "work" -> EmailLabelPair(Email.TYPE_WORK, "")
                "custom" -> EmailLabelPair(Email.TYPE_CUSTOM, customLabel)
                else -> EmailLabelPair(Email.TYPE_CUSTOM, label)
            }
        }

        private fun getAddressLabel(cursor: Cursor): String {
            val type = cursor.getInt(cursor.getColumnIndex(StructuredPostal.TYPE))
            return when (type) {
                StructuredPostal.TYPE_HOME -> "home"
                StructuredPostal.TYPE_OTHER -> "other"
                StructuredPostal.TYPE_WORK -> "work"
                StructuredPostal.TYPE_CUSTOM -> "custom"
                else -> ""
            }
        }

        private fun getAddressCustomLabel(cursor: Cursor): String {
            return cursor.getString(cursor.getColumnIndex(StructuredPostal.LABEL)) ?: ""
        }

        private data class AddressLabelPair(val label: Int, val customLabel: String)
        private fun getAddressLabelInv(label: String, customLabel: String): AddressLabelPair {
            return when (label) {
                "home" -> AddressLabelPair(StructuredPostal.TYPE_HOME, "")
                "other" -> AddressLabelPair(StructuredPostal.TYPE_OTHER, "")
                "work" -> AddressLabelPair(StructuredPostal.TYPE_WORK, "")
                "custom" -> AddressLabelPair(StructuredPostal.TYPE_CUSTOM, customLabel)
                else -> AddressLabelPair(StructuredPostal.TYPE_CUSTOM, label)
            }
        }

        private fun getWebsiteLabel(cursor: Cursor): String {
            val type = cursor.getInt(cursor.getColumnIndex(Website.TYPE))
            return when (type) {
                Website.TYPE_BLOG -> "blog"
                Website.TYPE_FTP -> "ftp"
                Website.TYPE_HOME -> "home"
                Website.TYPE_HOMEPAGE -> "homepage"
                Website.TYPE_OTHER -> "other"
                Website.TYPE_PROFILE -> "profile"
                Website.TYPE_WORK -> "work"
                Website.TYPE_CUSTOM -> "custom"
                else -> ""
            }
        }

        private fun getWebsiteCustomLabel(cursor: Cursor): String {
            return cursor.getString(cursor.getColumnIndex(Website.LABEL)) ?: ""
        }

        private data class WebsiteLabelPair(val label: Int, val customLabel: String)
        private fun getWebsiteLabelInv(label: String, customLabel: String): WebsiteLabelPair {
            return when (label) {
                "blog" -> WebsiteLabelPair(Website.TYPE_BLOG, "")
                "ftp" -> WebsiteLabelPair(Website.TYPE_FTP, "")
                "home" -> WebsiteLabelPair(Website.TYPE_HOME, "")
                "homepage" -> WebsiteLabelPair(Website.TYPE_HOMEPAGE, "")
                "other" -> WebsiteLabelPair(Website.TYPE_OTHER, "")
                "profile" -> WebsiteLabelPair(Website.TYPE_PROFILE, "")
                "work" -> WebsiteLabelPair(Website.TYPE_WORK, "")
                "custom" -> WebsiteLabelPair(StructuredPostal.TYPE_CUSTOM, customLabel)
                else -> WebsiteLabelPair(StructuredPostal.TYPE_CUSTOM, label)
            }
        }

        private fun getSocialMediaLabel(cursor: Cursor): String {
            val type = cursor.getInt(cursor.getColumnIndex(Im.PROTOCOL))
            return when (type) {
                Im.PROTOCOL_AIM -> "aim"
                Im.PROTOCOL_GOOGLE_TALK -> "googleTalk"
                Im.PROTOCOL_ICQ -> "icq"
                Im.PROTOCOL_JABBER -> "jabber"
                Im.PROTOCOL_MSN -> "msn"
                Im.PROTOCOL_NETMEETING -> "netmeeting"
                Im.PROTOCOL_QQ -> "qqchat"
                Im.PROTOCOL_SKYPE -> "skype"
                Im.PROTOCOL_YAHOO -> "yahoo"
                Im.PROTOCOL_CUSTOM -> "custom"
                else -> ""
            }
        }

        private fun getSocialMediaCustomLabel(cursor: Cursor): String {
            return cursor.getString(cursor.getColumnIndex(Im.CUSTOM_PROTOCOL)) ?: ""
        }

        private data class SocialMediaLabelPair(val label: Int, val customLabel: String)
        private fun getSocialMediaLabelInv(label: String, customLabel: String): SocialMediaLabelPair {
            return when (label) {
                "aim" -> SocialMediaLabelPair(Im.PROTOCOL_AIM, "")
                "googleTalk" -> SocialMediaLabelPair(Im.PROTOCOL_GOOGLE_TALK, "")
                "icq" -> SocialMediaLabelPair(Im.PROTOCOL_ICQ, "")
                "jabber" -> SocialMediaLabelPair(Im.PROTOCOL_JABBER, "")
                "msn" -> SocialMediaLabelPair(Im.PROTOCOL_MSN, "")
                "netmeeting" -> SocialMediaLabelPair(Im.PROTOCOL_NETMEETING, "")
                "qqchat" -> SocialMediaLabelPair(Im.PROTOCOL_QQ, "")
                "skype" -> SocialMediaLabelPair(Im.PROTOCOL_SKYPE, "")
                "yahoo" -> SocialMediaLabelPair(Im.PROTOCOL_YAHOO, "")
                "custom" -> SocialMediaLabelPair(StructuredPostal.TYPE_CUSTOM, customLabel)
                else -> SocialMediaLabelPair(StructuredPostal.TYPE_CUSTOM, label)
            }
        }

        private fun getEventLabel(cursor: Cursor): String {
            val type = cursor.getInt(cursor.getColumnIndex(Event.TYPE))
            return when (type) {
                Event.TYPE_ANNIVERSARY -> "anniversary"
                Event.TYPE_BIRTHDAY -> "birthday"
                Event.TYPE_OTHER -> "other"
                Event.TYPE_CUSTOM -> "custom"
                else -> ""
            }
        }

        private fun getEventCustomLabel(cursor: Cursor): String {
            return cursor.getString(cursor.getColumnIndex(Event.LABEL)) ?: ""
        }

        private data class EventLabelPair(val label: Int, val customLabel: String)
        private fun getEventLabelInv(label: String, customLabel: String): EventLabelPair {
            return when (label) {
                "anniversary" -> EventLabelPair(Event.TYPE_ANNIVERSARY, "")
                "birthday" -> EventLabelPair(Event.TYPE_BIRTHDAY, "")
                "other" -> EventLabelPair(Event.TYPE_OTHER, "")
                "custom" -> EventLabelPair(StructuredPostal.TYPE_CUSTOM, customLabel)
                else -> EventLabelPair(StructuredPostal.TYPE_CUSTOM, label)
            }
        }

        private fun buildOpsForContact(
            contact: Contact,
            ops: MutableList<ContentProviderOperation>,
            rawContactId: String? = null
        ) {
            fun emptyToNull(s: String): String? = if (s.isEmpty()) "" else s
            println(contact.displayName)
            println(rawContactId)
            fun newInsert(): ContentProviderOperation.Builder =
                if (rawContactId != null)
                    ContentProviderOperation
                        .newInsert(Data.CONTENT_URI)
                        .withValue(Data.RAW_CONTACT_ID, rawContactId)
                else
                    ContentProviderOperation
                        .newInsert(Data.CONTENT_URI)
                        .withValueBackReference(Data.RAW_CONTACT_ID, 0)

            val name: PName = contact.name
            ops.add(
                newInsert()
                    .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(StructuredName.GIVEN_NAME, emptyToNull(name.first))
                    .withValue(StructuredName.MIDDLE_NAME, emptyToNull(name.middle))
                    .withValue(StructuredName.FAMILY_NAME, emptyToNull(name.last))
                    .withValue(StructuredName.PREFIX, emptyToNull(name.prefix))
                    .withValue(StructuredName.SUFFIX, emptyToNull(name.suffix))
                    .withValue(StructuredName.PHONETIC_GIVEN_NAME, emptyToNull(name.firstPhonetic))
                    .withValue(StructuredName.PHONETIC_MIDDLE_NAME, emptyToNull(name.middlePhonetic))
                    .withValue(StructuredName.PHONETIC_FAMILY_NAME, emptyToNull(name.lastPhonetic))
                    .build()
            )
            if (!name.nickname.isEmpty()) {
                ops.add(
                    newInsert()
                        .withValue(Data.MIMETYPE, Nickname.CONTENT_ITEM_TYPE)
                        .withValue(Nickname.NAME, name.nickname)
                        .build()
                )
            }
            val phonesSelection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?"
            val phoneArgs = arrayOf(contact.id)

            // note we're running on Phone.CONTENT_URI, so no need to limit MIMTETYPE
            val phonesDelete = ContentProviderOperation.newDelete(ContactsContract.Phone.CONTENT_URI) 
                                    .withSelection(phonesSelection, phoneArgs)
                                    .build()
            ops.add(phonesDelete)
            for ((i, phone) in contact.phones.withIndex()) {
                println(phone.number)
                val labelPair: PhoneLabelPair = getPhoneLabelInv(phone.label, phone.customLabel)
                ops.add(
                    newInsert()
                        .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                        .withValue(Phone.NUMBER, emptyToNull(phone.number))
                        .withValue(Phone.TYPE, labelPair.label)
                        .withValue(Phone.LABEL, emptyToNull(labelPair.customLabel))
                        .withValue(Data.IS_PRIMARY, if (phone.isPrimary) 1 else 0)
                        .build()
                )
            }
            for ((i, email) in contact.emails.withIndex()) {
                val labelPair: EmailLabelPair = getEmailLabelInv(email.label, email.customLabel)
                ops.add(
                    newInsert()
                        .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                        .withValue(Email.ADDRESS, emptyToNull(email.address))
                        .withValue(Email.TYPE, labelPair.label)
                        .withValue(Email.LABEL, emptyToNull(labelPair.customLabel))
                        .withValue(Data.IS_PRIMARY, if (email.isPrimary) 1 else 0)
                        .build()
                )
            }
            for (address in contact.addresses) {
                val labelPair: AddressLabelPair =
                    getAddressLabelInv(address.label, address.customLabel)
                ops.add(
                    newInsert()
                        .withValue(Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
                        .withValue(StructuredPostal.FORMATTED_ADDRESS, emptyToNull(address.address))
                        .withValue(StructuredPostal.TYPE, labelPair.label)
                        .withValue(StructuredPostal.LABEL, emptyToNull(labelPair.customLabel))
                        .withValue(StructuredPostal.STREET, emptyToNull(address.street))
                        .withValue(StructuredPostal.POBOX, emptyToNull(address.pobox))
                        .withValue(StructuredPostal.NEIGHBORHOOD, emptyToNull(address.neighborhood))
                        .withValue(StructuredPostal.CITY, emptyToNull(address.city))
                        .withValue(StructuredPostal.REGION, emptyToNull(address.state))
                        .withValue(StructuredPostal.POSTCODE, emptyToNull(address.postalCode))
                        .withValue(StructuredPostal.COUNTRY, emptyToNull(address.country))
                        // isoCountry, subAmdinArea, subLocality not supported
                        .build()
                )
            }
            for (organization in contact.organizations) {
                ops.add(
                    newInsert()
                        .withValue(Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE)
                        .withValue(Organization.COMPANY, emptyToNull(organization.company))
                        .withValue(Organization.TITLE, emptyToNull(organization.title))
                        .withValue(Organization.DEPARTMENT, emptyToNull(organization.department))
                        .withValue(Organization.JOB_DESCRIPTION, emptyToNull(organization.jobDescription))
                        .withValue(Organization.SYMBOL, emptyToNull(organization.symbol))
                        .withValue(Organization.PHONETIC_NAME, emptyToNull(organization.phoneticName))
                        .withValue(Organization.OFFICE_LOCATION, emptyToNull(organization.officeLocation))
                        .build()
                )
            }
            for (website in contact.websites) {
                val labelPair: WebsiteLabelPair =
                    getWebsiteLabelInv(website.label, website.customLabel)
                ops.add(
                    newInsert()
                        .withValue(Data.MIMETYPE, Website.CONTENT_ITEM_TYPE)
                        .withValue(Website.URL, emptyToNull(website.url))
                        .withValue(Website.TYPE, labelPair.label)
                        .withValue(Website.LABEL, emptyToNull(labelPair.customLabel))
                        .build()
                )
            }
            for (socialMedia in contact.socialMedias) {
                val labelPair: SocialMediaLabelPair =
                    getSocialMediaLabelInv(socialMedia.label, socialMedia.customLabel)
                ops.add(
                    newInsert()
                        .withValue(Data.MIMETYPE, Im.CONTENT_ITEM_TYPE)
                        .withValue(Im.DATA, emptyToNull(socialMedia.userName))
                        .withValue(Im.PROTOCOL, labelPair.label)
                        .withValue(Im.CUSTOM_PROTOCOL, emptyToNull(labelPair.customLabel))
                        .build()
                )
            }
            for (event in contact.events) {
                val labelPair: EventLabelPair =
                    getEventLabelInv(event.label, event.customLabel)
                ops.add(
                    newInsert()
                        .withValue(Data.MIMETYPE, Event.CONTENT_ITEM_TYPE)
                        .withValue(Event.START_DATE, emptyToNull(event.date))
                        .withValue(Event.TYPE, labelPair.label)
                        .withValue(Event.LABEL, emptyToNull(labelPair.customLabel))
                        // noYear not supported
                        .build()
                )
            }
            for (note in contact.notes) {
                if (!note.note.isEmpty()) {
                    ops.add(
                        newInsert()
                            .withValue(Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
                            .withValue(Note.NOTE, note.note)
                            .build()
                    )
                }
            }
        }

        private fun buildOpsForPhoto(
            resolver: ContentResolver,
            photo: ByteArray,
            ops: MutableList<ContentProviderOperation>,
            rawContactId: Long
        ) {
            val photoUri: Uri = Uri.withAppendedPath(
                ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId),
                RawContacts.DisplayPhoto.CONTENT_DIRECTORY
            )
            var fd: AssetFileDescriptor? = resolver.openAssetFileDescriptor(photoUri, "rw")
            if (fd != null) {
                val os: OutputStream = fd.createOutputStream()
                os.write(photo)
                os.close()
                fd.close()
            }
        }
    }
}
