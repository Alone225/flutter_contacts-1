import 'package:after_layout/after_layout.dart';
import 'package:flutter/material.dart';
import 'package:flutter_contacts/flutter_contacts.dart';
import 'package:flutter_contacts_example/pages/form_components/address_form.dart';
import 'package:flutter_contacts_example/pages/form_components/email_form.dart';
import 'package:flutter_contacts_example/pages/form_components/event_form.dart';
import 'package:flutter_contacts_example/pages/form_components/name_form.dart';
import 'package:flutter_contacts_example/pages/form_components/note_form.dart';
import 'package:flutter_contacts_example/pages/form_components/organization_form.dart';
import 'package:flutter_contacts_example/pages/form_components/phone_form.dart';
import 'package:flutter_contacts_example/pages/form_components/social_media_form.dart';
import 'package:flutter_contacts_example/pages/form_components/website_form.dart';
import 'package:flutter_contacts_example/util/avatar.dart';
import 'package:image_picker/image_picker.dart';
import 'package:pretty_json/pretty_json.dart';

class EditContactPage extends StatefulWidget {
  @override
  _EditContactPageState createState() => _EditContactPageState();
}

class _EditContactPageState extends State<EditContactPage>
    with AfterLayoutMixin<EditContactPage> {
  var _contact = Contact.create();
  bool _isEdit = false;
  void Function() _onUpdate;
  var _deletePhoto = false;

  final _imagePicker = ImagePicker();

  @override
  void afterFirstLayout(BuildContext context) {
    final args =
        ModalRoute.of(context).settings.arguments as Map<String, dynamic>;
    if (args != null) {
      setState(() {
        _contact = args['contact'];
        _isEdit = true;
        _onUpdate = args['onUpdate'];
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('${_isEdit ? 'Edit' : 'New'} contact'),
        actions: <Widget>[
          IconButton(
            icon: Icon(Icons.remove_red_eye),
            onPressed: () async {
              await showDialog(
                context: context,
                builder: (_) => AlertDialog(
                  content: Text(prettyJson(_contact.toJson())),
                ),
              );
            },
          ),
          IconButton(
            icon: Icon(Icons.save),
            onPressed: () async {
              String name;
              if (_isEdit) {
                await FlutterContacts.updateContact(_contact,
                    deletePhoto: _deletePhoto);
                name = _contact.displayName;
              } else {
                _contact = await FlutterContacts.newContact(_contact);
                name = _contact.displayName;
              }
              ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                  content:
                      Text('${_isEdit ? 'Updated' : 'Saved'} contact $name')));
              if (_onUpdate != null) _onUpdate();
              Navigator.of(context).pop();
            },
          ),
        ],
      ),
      body: _body(),
    );
  }

  Widget _body() {
    return SingleChildScrollView(
      child: Padding(
        padding: EdgeInsets.all(8),
        child: Form(
          child: Column(
            children: _contactFields(),
          ),
        ),
      ),
    );
  }

  List<Widget> _contactFields() => [
        _photoField(),
        _nameCard(),
        _phoneCard(),
        _emailCard(),
        _addressCard(),
        _organizationCard(),
        _websiteCard(),
        _socialMediaCard(),
        _eventCard(),
        _noteCard(),
      ];

  Future _pickPhoto() async {
    final photo = await _imagePicker.getImage(source: ImageSource.camera);
    if (photo != null) {
      final bytes = await photo.readAsBytes();
      setState(() {
        _contact.photo = bytes;
        _deletePhoto = false;
      });
    }
  }

  Widget _photoField() => Stack(children: [
        Center(
            child: InkWell(
          child: avatar(_contact, 48, Icons.add),
          onTap: _pickPhoto,
        )),
        _contact.photo == null
            ? Container()
            : Align(
                alignment: Alignment.topRight,
                child: PopupMenuButton(
                  itemBuilder: (context) => [
                    PopupMenuItem(child: Text('Delete photo'), value: 'Delete')
                  ],
                  onSelected: (_) => setState(() {
                    _contact.photo = null;
                    _deletePhoto = true;
                  }),
                ),
              ),
      ]);

  Card _fieldCard(
    String fieldName,
    List<dynamic> fields,
    /* void | Future<void> */ Function() addField,
    Widget Function(int, dynamic) formWidget, {
    bool createAsync = false,
  }) {
    var forms = <Widget>[
      Text(fieldName, style: TextStyle(fontSize: 18)),
    ];
    fields.asMap().forEach((int i, dynamic p) => forms.add(formWidget(i, p)));
    void Function() onPressed;
    if (createAsync) {
      onPressed = () async {
        await addField();
        setState(() {});
      };
    } else {
      onPressed = () => setState(() {
            addField();
          });
    }
    if (addField != null) {
      forms.add(
        RaisedButton(
          child: Text('+ New'),
          onPressed: onPressed,
        ),
      );
    }

    return Card(
      margin: EdgeInsets.all(12.0),
      child: Padding(
        padding: const EdgeInsets.all(8.0),
        child: SizedBox(
          width: double.infinity,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: forms,
          ),
        ),
      ),
    );
  }

  Card _nameCard() => _fieldCard(
        'Name',
        [_contact.name],
        null,
        (int i, dynamic n) => NameForm(
          n,
          onUpdate: (name) => _contact.name = name,
          key: UniqueKey(),
        ),
      );

  Card _phoneCard() => _fieldCard(
        'Phones',
        _contact.phones,
        () => _contact.phones = _contact.phones + [Phone('')],
        (int i, dynamic p) => PhoneForm(
          p,
          onUpdate: (phone) => _contact.phones[i] = phone,
          onDelete: () => setState(() => _contact.phones.removeAt(i)),
          key: UniqueKey(),
        ),
      );

  Card _emailCard() => _fieldCard(
        'Emails',
        _contact.emails,
        () => _contact.emails = _contact.emails + [Email('')],
        (int i, dynamic e) => EmailForm(
          e,
          onUpdate: (email) => _contact.emails[i] = email,
          onDelete: () => setState(() => _contact.emails.removeAt(i)),
          key: UniqueKey(),
        ),
      );

  Card _addressCard() => _fieldCard(
        'Addresses',
        _contact.addresses,
        () => _contact.addresses = _contact.addresses + [Address('')],
        (int i, dynamic a) => AddressForm(
          a,
          onUpdate: (address) => _contact.addresses[i] = address,
          onDelete: () => setState(() => _contact.addresses.removeAt(i)),
          key: UniqueKey(),
        ),
      );

  Card _organizationCard() => _fieldCard(
        'Organizations',
        _contact.organizations,
        () =>
            _contact.organizations = _contact.organizations + [Organization()],
        (int i, dynamic o) => OrganizationForm(
          o,
          onUpdate: (organization) => _contact.organizations[i] = organization,
          onDelete: () => setState(() => _contact.organizations.removeAt(i)),
          key: UniqueKey(),
        ),
      );

  Card _websiteCard() => _fieldCard(
        'Websites',
        _contact.websites,
        () => _contact.websites = _contact.websites + [Website('')],
        (int i, dynamic w) => WebsiteForm(
          w,
          onUpdate: (website) => _contact.websites[i] = website,
          onDelete: () => setState(() => _contact.websites.removeAt(i)),
          key: UniqueKey(),
        ),
      );

  Card _socialMediaCard() => _fieldCard(
        'Social medias',
        _contact.socialMedias,
        () => _contact.socialMedias = _contact.socialMedias + [SocialMedia('')],
        (int i, dynamic w) => SocialMediaForm(
          w,
          onUpdate: (socialMedia) => _contact.socialMedias[i] = socialMedia,
          onDelete: () => setState(() => _contact.socialMedias.removeAt(i)),
          key: UniqueKey(),
        ),
      );

  Future<DateTime> _selectDate(BuildContext context) async => showDatePicker(
      context: context,
      initialDate: DateTime.now(),
      firstDate: DateTime(1900),
      lastDate: DateTime(3000));

  Card _eventCard() => _fieldCard(
        'Events',
        _contact.events,
        () async {
          final date = await _selectDate(context);
          if (date != null) _contact.events = _contact.events + [Event(date)];
        },
        (int i, dynamic w) => EventForm(
          w,
          onUpdate: (event) => _contact.events[i] = event,
          onDelete: () => setState(() => _contact.events.removeAt(i)),
          key: UniqueKey(),
        ),
        createAsync: true,
      );

  Card _noteCard() => _fieldCard(
        'Notes',
        _contact.notes,
        () => _contact.notes = _contact.notes + [Note('')],
        (int i, dynamic w) => NoteForm(
          w,
          onUpdate: (note) => _contact.notes[i] = note,
          onDelete: () => setState(() => _contact.notes.removeAt(i)),
          key: UniqueKey(),
        ),
      );
}
