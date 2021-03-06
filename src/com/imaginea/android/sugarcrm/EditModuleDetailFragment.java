package com.imaginea.android.sugarcrm;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.provider.BaseColumns;
import android.provider.ContactsContract;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AutoCompleteTextView;
import android.widget.CursorAdapter;
import android.widget.Filterable;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.imaginea.android.sugarcrm.CustomActionbar.AbstractAction;
import com.imaginea.android.sugarcrm.provider.ContentUtils;
import com.imaginea.android.sugarcrm.provider.DatabaseHelper;
import com.imaginea.android.sugarcrm.provider.SugarCRMContent.Accounts;
import com.imaginea.android.sugarcrm.provider.SugarCRMContent.AccountsColumns;
import com.imaginea.android.sugarcrm.provider.SugarCRMContent.UserColumns;
import com.imaginea.android.sugarcrm.provider.SugarCRMContent.Users;
import com.imaginea.android.sugarcrm.ui.BaseActivity;
import com.imaginea.android.sugarcrm.util.ImportContactsUtility;
import com.imaginea.android.sugarcrm.util.ModuleField;
import com.imaginea.android.sugarcrm.util.ModuleFieldValidator;
import com.imaginea.android.sugarcrm.util.Util;
import com.imaginea.android.sugarcrm.util.ViewUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p>
 * EditModuleDetailFragment class.
 * </p>
 * 
 */
public class EditModuleDetailFragment extends Fragment {

    private final static String TAG = "EditModuleDetail";

    private int MODE = -1;

    private ViewGroup mDetailsTable;

    private Cursor mCursor;

    private String mSugarBeanId;

    private String mModuleName;

    private String mRowId;

    private int importFlag;

    private String[] mSelectFields;

    private Uri mIntentUri;

    private DatabaseHelper mDbHelper;

    private LoadContentTask mTask;

    private Messenger mMessenger;

    private StatusHandler mStatusHandler;

    private AutoSuggestAdapter mAccountAdapter;

    private AutoSuggestAdapter mUserAdapter;

    private Cursor mAccountCursor;

    private Cursor mUserCursor;

    private String mSelectedAccountName;

    private String mSelectedUserName;

    private String mAccountName;

    private String mUserName;

    private ProgressDialog mProgressDialog;

    private boolean hasError;

    private RelativeLayout mParent;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        mParent = (RelativeLayout) inflater.inflate(R.layout.edit_details, container, false);

        return mParent;
    }

    /** {@inheritDoc} */
    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mDbHelper = new DatabaseHelper(this.getActivity());
        final Intent intent = BaseActivity.fragmentArgumentsToIntent(getArguments());
        // Intent intent = getActivity().getIntent();
        Bundle extras = intent.getExtras();

        mModuleName = Util.CONTACTS;
        if (extras != null) {
            // i always get the module name
            mModuleName = extras.getString(RestUtilConstants.MODULE_NAME);
            importFlag = extras.getInt(Util.IMPORT_FLAG);
            mRowId = intent.getStringExtra(Util.ROW_ID);
            mSugarBeanId = intent.getStringExtra(RestUtilConstants.BEAN_ID);
        }
        // when the user comes from the relationships, intent.getData() won't be
        // null
        mIntentUri = intent.getData();
        Log.v(TAG, "uri - " + (mIntentUri != null ? mIntentUri : ""));

        if (intent.getData() != null && mRowId != null) {
            MODE = Util.EDIT_RELATIONSHIP_MODE;
        } else if (mRowId != null) {
            MODE = Util.EDIT_ORPHAN_MODE;
        } else if (intent.getData() != null) {
            MODE = Util.NEW_RELATIONSHIP_MODE;
        } else {
            MODE = Util.NEW_ORPHAN_MODE;
        }

        Log.v(TAG, "mode - " + MODE);

        if (intent.getData() == null && MODE == Util.EDIT_ORPHAN_MODE) {
            mIntentUri = Uri.withAppendedPath(ContentUtils.getModuleUri(mModuleName), mRowId);
            intent.setData(mIntentUri);
        } else if (intent.getData() == null && MODE == Util.NEW_ORPHAN_MODE) {
            mIntentUri = ContentUtils.getModuleUri(mModuleName);
            intent.setData(mIntentUri);
        }

        mSelectFields = ContentUtils.getModuleProjections(mModuleName);

        /*
         * if (MODE == Util.EDIT_ORPHAN_MODE || MODE == Util.EDIT_RELATIONSHIP_MODE) { mCursor =
         * getContentResolver().query(getIntent().getData(), mSelectFields, null, null,
         * mDbHelper.getModuleSortOrder(mModuleName)); }
         */
        // startManagingCursor(mCursor);
        // setContents();

        mTask = new LoadContentTask(getActivity());
        mTask.execute(null, null, null);

        if (importFlag == Util.CONTACT_IMPORT_FLAG) {
            importContact();
        }

        final CustomActionbar actionBar = (CustomActionbar) mParent.getChildAt(0);
        //final Action homeAction = new IntentAction(EditModuleDetailFragment.this.getActivity(), new Intent(EditModuleDetailFragment.this.getActivity(), DashboardActivity.class), R.drawable.home);
        actionBar.setHomeAction(new HomeAction());
        actionBar.addActionItem(new SaveAction());       

        // our xml onClick items no longer work - so have to set these explicitly again- YUCK
        /*
         * getActivity().findViewById(R.id.save).setOnClickListener(new View.OnClickListener() {
         * 
         * @Override public void onClick(View v) { saveModuleItem(v); } });
         */
    }

    /** {@inheritDoc} */
    @Override
    public void onPause() {
        super.onPause();
        SugarService.unregisterMessenger(mMessenger);
        if (mTask != null && mTask.getStatus() == AsyncTask.Status.RUNNING) {
            mTask.cancel(true);
        }

        if (mAccountCursor != null)
            mAccountCursor.close();

        if (mUserCursor != null)
            mUserCursor.close();

    }

    /** {@inheritDoc} */
    @Override
    public void onResume() {
        super.onResume();
        if (mMessenger == null) {
            mStatusHandler = new StatusHandler();
            mMessenger = new Messenger(mStatusHandler);
        }
        SugarService.registerMessenger(mMessenger);
    }

    class LoadContentTask extends AsyncTask<Object, Object, Object> {

        int staticRowsCount;
        
        Context mContext;

        final static int STATIC_ROW = 1;

        final static int DYNAMIC_ROW = 2;

        final static int SAVE_BUTTON = 3;

        final static int INPUT_TYPE = 4;

        LoadContentTask(Context context) {
        	mContext = context;
            mDetailsTable = (ViewGroup) getActivity().findViewById(R.id.moduleDetailsTable);

            // as the last child is the SAVE button, count - 1 has to be done.
            staticRowsCount = mDetailsTable.getChildCount() - 1;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            // TextView tv = (TextView) getActivity().findViewById(R.id.headerText);
            final CustomActionbar tv = (CustomActionbar) mParent.getChildAt(0);
            if (MODE == Util.EDIT_ORPHAN_MODE || MODE == Util.EDIT_RELATIONSHIP_MODE) {
                tv.setTitle(String.format(getString(R.string.editDetailsHeader), mModuleName));
            } else if (MODE == Util.NEW_ORPHAN_MODE || MODE == Util.NEW_RELATIONSHIP_MODE) {
                tv.setTitle(String.format(getString(R.string.newDetailsHeader), mModuleName));
            }

            mAccountCursor = getActivity().getContentResolver().query(ContentUtils.getModuleUri(Util.ACCOUNTS), Accounts.LIST_PROJECTION, null, null, null);
            mAccountAdapter = new AccountsSuggestAdapter(getActivity().getBaseContext(), mAccountCursor);

            mUserCursor = getActivity().getContentResolver().query(ContentUtils.getModuleUri(Util.USERS), Users.DETAILS_PROJECTION, null, null, null);
            mUserAdapter = new UsersSuggestAdapter(getActivity().getBaseContext(), mUserCursor);

            mProgressDialog = ViewUtil.getProgressDialog(EditModuleDetailFragment.this.getActivity(), getString(R.string.loading), false);
            mProgressDialog.show();
        }

        @Override
        protected void onProgressUpdate(Object... values) {
            super.onProgressUpdate(values);

            switch ((Integer) values[0]) {

            case STATIC_ROW:
                final String fieldName = (String) values[1];
                View editRow = (View) values[2];
                editRow.setVisibility(View.VISIBLE);

                TextView labelView = (TextView) values[3];
                labelView.setText((String) values[4]);
                final AutoCompleteTextView valueView = (AutoCompleteTextView) values[5];
                valueView.setTag(fieldName);
                String editTextValue = (String) values[6];
                valueView.setText(editTextValue);

                if (fieldName.equalsIgnoreCase(ImportContactsUtility.getModuleFieldNameForContactsField(ModuleFields.EMAIL1))) {
                    valueView.setValidator(new AutoCompleteTextView.Validator() {
                        @Override
                        public CharSequence fixText(CharSequence invalidText) {
                            return invalidText;
                        }

                        @Override
                        public boolean isValid(CharSequence text) {
                            String email = text.toString();
                            if (!ModuleFieldValidator.isNotEmpty(email)
                                                            || !ModuleFieldValidator.isEmailValid(email)) {
                                hasError = true;
                                valueView.setError(getString(R.string.emailValidationErrorMsg));
                            }
                            return false;
                        }
                    });
                }

                if (fieldName.equalsIgnoreCase(ImportContactsUtility.getModuleFieldNameForContactsField(ModuleFields.PHONE_MOBILE))
                                                || fieldName.equalsIgnoreCase(ImportContactsUtility.getModuleFieldNameForContactsField(ModuleFields.PHONE_WORK))) {
                    valueView.setInputType(InputType.TYPE_CLASS_NUMBER);
                    valueView.setValidator(new AutoCompleteTextView.Validator() {
                        @Override
                        public CharSequence fixText(CharSequence invalidText) {
                            return invalidText;
                        }

                        @Override
                        public boolean isValid(CharSequence text) {
                            String phoneNumber = text.toString();
                            if (ModuleFieldValidator.isNotEmpty(phoneNumber)
                                                            && !ModuleFieldValidator.isPhoneNumberValid(phoneNumber)) {
                                hasError = true;
                                valueView.setError(getString(R.string.phNoValidationErrorMsg));
                            }
                            return false;
                        }
                    });
                }

                if (fieldName.equalsIgnoreCase(ImportContactsUtility.getModuleFieldNameForContactsField(ModuleFields.FIRST_NAME))
                                                || fieldName.equalsIgnoreCase(ImportContactsUtility.getModuleFieldNameForContactsField(ModuleFields.LAST_NAME))) {
                    valueView.setValidator(new AutoCompleteTextView.Validator() {
                        @Override
                        public CharSequence fixText(CharSequence invalidText) {
                            return invalidText;
                        }

                        @Override
                        public boolean isValid(CharSequence text) {
                            if (!ModuleFieldValidator.isNotEmpty(text.toString())) {
                                hasError = true;
                                valueView.setError(String.format(getString(R.string.emptyValidationErrorMsg), fieldName));
                            }
                            return false;
                        }
                    });
                }

                // set the adapter to auto-suggest
                if (!Util.ACCOUNTS.equals(mModuleName)
                                                && fieldName.equals(ModuleFields.ACCOUNT_NAME)) {
                    // only if the module is directly related to Accounts,
                    // disable the account name
                    // field populating it with the corresponding account name
                    if (MODE == Util.NEW_RELATIONSHIP_MODE) {

                        // get the module name from the URI
                        String module = mIntentUri.getPathSegments().get(0);

                        // only if the module is directly related with the
                        // Accounts modulemenu in
                        if (Util.ACCOUNTS.equals(module)) {

                            if (mDbHelper == null)
                                mDbHelper = new DatabaseHelper(getActivity().getBaseContext());

                            // get the account name using the account row id in
                            // the URI
                            int accountRowId = Integer.parseInt(mIntentUri.getPathSegments().get(1));
                            String selection = AccountsColumns.ID + "=" + accountRowId;
                            Cursor cursor = getActivity().getContentResolver().query(ContentUtils.getModuleUri(Util.ACCOUNTS), Accounts.LIST_PROJECTION, selection, null, null);
                            cursor.moveToFirst();
                            String accountName = cursor.getString(2);
                            cursor.close();

                            // pre-populate the field with the account name and
                            // disable it
                            valueView.setText(accountName);
                            valueView.setEnabled(false);
                        } else {
                            // if the module is not directly related to Accounts
                            // module, show the
                            // auto-suggest instead of pre-populating the
                            // account name field
                            valueView.setAdapter(mAccountAdapter);
                            valueView.setOnItemClickListener(new AccountsClickedItemListener());
                        }
                    } else {
                        // set the adapter to show the auto-suggest
                        valueView.setAdapter(mAccountAdapter);
                        valueView.setOnItemClickListener(new AccountsClickedItemListener());

                        if (MODE == Util.EDIT_ORPHAN_MODE || MODE == Util.EDIT_RELATIONSHIP_MODE)
                            // store the account name in mAccountName if the
                            // bean is already related
                            // to an account
                            if (!TextUtils.isEmpty(editTextValue)) {
                                mAccountName = editTextValue;
                            }
                    }
                } else if (fieldName.equals(ModuleFields.ASSIGNED_USER_NAME)) {
                    // set the adapter to show the auto-suggest
                    valueView.setAdapter(mUserAdapter);
                    valueView.setOnItemClickListener(new UsersClickedItemListener());

                    if (MODE == Util.EDIT_ORPHAN_MODE || MODE == Util.EDIT_RELATIONSHIP_MODE) {
                        // store the user name in mUserName if the bean is
                        // already assigned to a
                        // user
                        if (!TextUtils.isEmpty(editTextValue)) {
                            mUserName = editTextValue;
                        }
                    }
                }
                break;

            case DYNAMIC_ROW:
                final String dynamicFieldName = (String) values[1];

                editRow = (View) values[2];
                editRow.setVisibility(View.VISIBLE);

                labelView = (TextView) values[3];
                labelView.setText((String) values[4]);
                final AutoCompleteTextView dynamicValueView = (AutoCompleteTextView) values[5];
                dynamicValueView.setTag(dynamicFieldName);
                editTextValue = (String) values[6];
                dynamicValueView.setText(editTextValue);

                if (dynamicFieldName.equalsIgnoreCase(ImportContactsUtility.getModuleFieldNameForContactsField(ModuleFields.EMAIL1))) {
                    dynamicValueView.setValidator(new AutoCompleteTextView.Validator() {
                        @Override
                        public CharSequence fixText(CharSequence invalidText) {
                            return invalidText;
                        }

                        @Override
                        public boolean isValid(CharSequence text) {
                            String email = text.toString();
                            if (!ModuleFieldValidator.isNotEmpty(email)
                                                            || !ModuleFieldValidator.isEmailValid(email)) {
                                hasError = true;
                                dynamicValueView.setError(getString(R.string.emailValidationErrorMsg));
                            }
                            return false;
                        }
                    });
                }

                if (dynamicFieldName.equalsIgnoreCase(ImportContactsUtility.getModuleFieldNameForContactsField(ModuleFields.PHONE_MOBILE))
                                                || dynamicFieldName.equalsIgnoreCase(ImportContactsUtility.getModuleFieldNameForContactsField(ModuleFields.PHONE_WORK))) {
                    dynamicValueView.setInputType(InputType.TYPE_CLASS_NUMBER);
                    dynamicValueView.setValidator(new AutoCompleteTextView.Validator() {
                        @Override
                        public CharSequence fixText(CharSequence invalidText) {
                            return invalidText;
                        }

                        @Override
                        public boolean isValid(CharSequence text) {
                            String phoneNumber = text.toString();
                            if (ModuleFieldValidator.isNotEmpty(phoneNumber)
                                                            && !ModuleFieldValidator.isPhoneNumberValid(phoneNumber)) {
                                hasError = true;
                                dynamicValueView.setError(getString(R.string.phNoValidationErrorMsg));
                            }
                            return false;
                        }
                    });
                }

                if (dynamicFieldName.equalsIgnoreCase(ImportContactsUtility.getModuleFieldNameForContactsField(ModuleFields.FIRST_NAME))
                                                || dynamicFieldName.equalsIgnoreCase(ImportContactsUtility.getModuleFieldNameForContactsField(ModuleFields.LAST_NAME))) {
                    dynamicValueView.setValidator(new AutoCompleteTextView.Validator() {
                        @Override
                        public CharSequence fixText(CharSequence invalidText) {
                            return invalidText;
                        }

                        @Override
                        public boolean isValid(CharSequence text) {
                            if (!ModuleFieldValidator.isNotEmpty(text.toString())) {
                                hasError = true;
                                dynamicValueView.setError(String.format(getString(R.string.emptyValidationErrorMsg), dynamicFieldName));
                            }
                            return false;
                        }
                    });
                }

                // set the adapter to auto-suggest
                if (!Util.ACCOUNTS.equals(mModuleName)
                                                && dynamicFieldName.equals(ModuleFields.ACCOUNT_NAME)) {
                    /*
                     * only if the module is directly related to Accounts, disable the account name
                     * field populating it with the corresponding account name
                     */
                    if (MODE == Util.NEW_RELATIONSHIP_MODE) {

                        // get the module name from the URI
                        String module = mIntentUri.getPathSegments().get(0);

                        // only if the module is directly related with the
                        // Accounts module
                        if (Util.ACCOUNTS.equals(module)) {

                            if (mDbHelper == null)
                                mDbHelper = new DatabaseHelper(getActivity().getBaseContext());

                            // get the account name using the account row id
                            // in
                            // the URI
                            int accountRowId = Integer.parseInt(mIntentUri.getPathSegments().get(1));
                            String selection = AccountsColumns.ID + "=" + accountRowId;
                            Cursor cursor = getActivity().getContentResolver().query(ContentUtils.getModuleUri(Util.ACCOUNTS), Accounts.LIST_PROJECTION, selection, null, null);
                            cursor.moveToFirst();
                            String accountName = cursor.getString(2);
                            cursor.close();

                            // pre-populate the field with the account name
                            // and
                            // disable it
                            dynamicValueView.setText(accountName);
                            dynamicValueView.setEnabled(false);
                        } else {
                            // if the module is not directly related to
                            // Accounts
                            // module, show the
                            // auto-suggest instead of pre-populating the
                            // account name field
                            dynamicValueView.setAdapter(mAccountAdapter);
                            dynamicValueView.setOnItemClickListener(new AccountsClickedItemListener());
                        }
                    } else {
                        // set the apadter to show the auto-suggest
                        dynamicValueView.setAdapter(mAccountAdapter);
                        dynamicValueView.setOnItemClickListener(new AccountsClickedItemListener());

                        if (MODE == Util.EDIT_ORPHAN_MODE || MODE == Util.EDIT_RELATIONSHIP_MODE)
                            // store the account name in mAccountName if the
                            // bean is already related
                            // to an account
                            if (!TextUtils.isEmpty(editTextValue)) {
                                mAccountName = editTextValue;
                            }
                    }
                } else if (dynamicFieldName.equals(ModuleFields.ASSIGNED_USER_NAME)) {
                    // set the adapter to show the auto-suggest
                    dynamicValueView.setAdapter(mUserAdapter);
                    dynamicValueView.setOnItemClickListener(new UsersClickedItemListener());

                    if (MODE == Util.EDIT_ORPHAN_MODE || MODE == Util.EDIT_RELATIONSHIP_MODE) {
                        // store the user name in mUserName if the bean is
                        // already assigned to a
                        // user
                        if (!TextUtils.isEmpty(editTextValue)) {
                            mUserName = editTextValue;
                        }
                    }
                }

                mDetailsTable.addView(editRow);
                break;

            case INPUT_TYPE:
                AutoCompleteTextView inputTypeValueView = (AutoCompleteTextView) values[1];
                inputTypeValueView.setInputType((Integer) values[2]);
                break;

            }
        }

        @Override
        protected Object doInBackground(Object... params) {
            try {
                if (MODE == Util.EDIT_ORPHAN_MODE || MODE == Util.EDIT_RELATIONSHIP_MODE) {
                    mCursor = getActivity().getContentResolver().query(Uri.withAppendedPath(ContentUtils.getModuleUri(mModuleName), mRowId),
                    		mSelectFields, null, null, ContentUtils.getModuleSortOrder(mModuleName));
                }
                // setContents();
            } catch (Exception e) {
                Log.e(TAG, e.getMessage(), e);
                return Util.FETCH_FAILED;
            }

            return Util.FETCH_SUCCESS;
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();

        }

        @Override
        protected void onPostExecute(Object result) {
            super.onPostExecute(result);

            setContents();

            // close the cursor irrespective of the result
            if (mCursor != null && !mCursor.isClosed())
                mCursor.close();

            if (isCancelled())
                return;
            int retVal = (Integer) result;
            switch (retVal) {
            case Util.FETCH_FAILED:
                break;
            case Util.FETCH_SUCCESS:
                // set visibility for the SAVE button
                LinearLayout parent = (LinearLayout) getActivity().findViewById(R.id.actionbar_items);
                parent.getChildAt(0).setVisibility(View.VISIBLE);
                break;
            default:
            }

            mProgressDialog.cancel();
        }

        private void setContents() {

            String[] detailsProjection = mSelectFields;

            if (mDbHelper == null)
                mDbHelper = new DatabaseHelper(getActivity().getBaseContext());

            if (MODE == Util.EDIT_ORPHAN_MODE || MODE == Util.EDIT_RELATIONSHIP_MODE) {
                if (!isCancelled()) {
                    mCursor.moveToFirst();
                    mSugarBeanId = mCursor.getString(1); // beanId has
                    // columnIndex 1
                }
            }

            LayoutInflater inflater = (LayoutInflater) getActivity().getBaseContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);

            Map<String, ModuleField> fieldNameVsModuleField = ContentUtils.getModuleFields(mContext, mModuleName);
            Map<String, String> fieldsExcludedForEdit = mDbHelper.getFieldsExcludedForEdit();

            int rowsCount = 0; // to keep track of number of rows being used
            for (int i = 0; i < detailsProjection.length; i++) {
                // if the task gets cancelled
                if (isCancelled())
                    break;

                String fieldName = detailsProjection[i];

                // if the field name is excluded in details screen, skip it
                if (fieldsExcludedForEdit.containsKey(fieldName)) {
                    continue;
                }

                // get the attributes of the moduleField
                ModuleField moduleField = fieldNameVsModuleField.get(fieldName);

                ViewGroup tableRow;
                TextView textViewForLabel;
                AutoCompleteTextView editTextForValue;
                if (staticRowsCount > rowsCount) {
                    tableRow = (ViewGroup) mDetailsTable.getChildAt(rowsCount);
                    textViewForLabel = (TextView) tableRow.getChildAt(0);
                    editTextForValue = (AutoCompleteTextView) tableRow.getChildAt(1);
                } else {
                    tableRow = (ViewGroup) inflater.inflate(R.layout.edit_table_row, null);
                    textViewForLabel = (TextView) tableRow.getChildAt(0);
                    editTextForValue = (AutoCompleteTextView) tableRow.getChildAt(1);
                }

                String label;
                if (moduleField.isRequired()) {
                    label = moduleField.getLabel() + " *";
                } else {
                    label = moduleField.getLabel();
                }

                int command = STATIC_ROW;
                if (staticRowsCount < rowsCount) {
                    command = DYNAMIC_ROW;
                }

                if (MODE == Util.EDIT_ORPHAN_MODE || MODE == Util.EDIT_RELATIONSHIP_MODE) {
                    String value = mCursor.getString(mCursor.getColumnIndex(fieldName));
                    if (!TextUtils.isEmpty(value)) {
                        publishProgress(command, fieldName, tableRow, textViewForLabel, label, editTextForValue, value);
                    } else {
                        publishProgress(command, fieldName, tableRow, textViewForLabel, label, editTextForValue, "");
                    }
                    setInputType(editTextForValue, moduleField);

                } else {
                    publishProgress(command, fieldName, tableRow, textViewForLabel, label, editTextForValue, "");
                }
                rowsCount++;
            }

        }

        /*
         * takes care of basic validation automatically for some fields
         */
        private void setInputType(TextView editTextForValue, ModuleField moduleField) {
            if (Log.isLoggable(TAG, Log.VERBOSE))
                Log.v(TAG, "ModuleField type:" + moduleField.getType());
            // TODO: there has to be a better way to get the constant
            if (moduleField.getType().equals("phone")) {
                // editTextForValue.setInputType(InputType.TYPE_CLASS_PHONE);
                publishProgress(INPUT_TYPE, editTextForValue, InputType.TYPE_CLASS_PHONE);
            }
        }
    }

    /**
     * on click listener for saving a module item
     * 
     * @param v
     *            a {@link android.view.View} object.
     */
    public void saveModuleItem(View v) {

        mProgressDialog = ViewUtil.getProgressDialog(EditModuleDetailFragment.this.getActivity(), getString(R.string.saving), false);
        mProgressDialog.show();

        String[] detailsProjection = mSelectFields;

        Map<String, String> modifiedValues = new LinkedHashMap<String, String>();
        if (MODE == Util.EDIT_ORPHAN_MODE || MODE == Util.EDIT_RELATIONSHIP_MODE)
            modifiedValues.put(RestUtilConstants.ID, mSugarBeanId);

        Uri uri = mIntentUri;

        Map<String, String> fieldsExcludedForEdit = mDbHelper.getFieldsExcludedForEdit();
        int rowsCount = 0;
        for (int i = 0; i < detailsProjection.length; i++) {

            String fieldName = detailsProjection[i];

            // if the field name is excluded in details screen, skip it
            if (fieldsExcludedForEdit.containsKey(fieldName)) {
                continue;
            }

            AutoCompleteTextView editText = (AutoCompleteTextView) ((ViewGroup) mDetailsTable.getChildAt(rowsCount)).getChildAt(1);
            String fieldValue = editText.getText().toString();

            if (!Util.ACCOUNTS.equals(mModuleName) && fieldName.equals(ModuleFields.ACCOUNT_NAME)) {

                if (!TextUtils.isEmpty(fieldValue)) {

                    if (!TextUtils.isEmpty(mSelectedAccountName)) {
                        // if the user has selected an account from the
                        // auto-suggest list

                        // check if the field value is the selected value
                        if (!mSelectedAccountName.equals(fieldValue)) {
                            // account name is incorrect.
                            hasError = true;
                            editText.setError(getString(R.string.accountNameErrorMsg));
                        }

                    } else if (!TextUtils.isEmpty(mAccountName)) {
                        // if the user doesn't change the account name and it
                        // remains the same

                        if (!fieldValue.equals(mAccountName)) {
                            // if the user just enters some value without
                            // selecting from the
                            // auto-suggest
                            hasError = true;
                            editText.setError(getString(R.string.accountNameErrorMsg));
                        }

                    } else {
                        // if the editText has been disabled, do not show the
                        // error
                        if (editText.isEnabled()) {
                            // if the user just enters some value without
                            // selecting((ViewGroup)
                            // mDetailsTable.getChildAt(rowsCount)).getChildAt(1)
                            // from the
                            // auto-suggest
                            hasError = true;
                            editText.setError(getString(R.string.accountNameErrorMsg));
                        }
                    }

                } else {
                    // if the fieldValue is empty
                    fieldValue = null;
                }

            } else if (fieldName.equals(ModuleFields.ASSIGNED_USER_NAME)) {

                if (!TextUtils.isEmpty(fieldValue)) {

                    if (!TextUtils.isEmpty(mSelectedUserName)) {
                        // if the user has selected a user name from the
                        // auto-suggest list

                        // check if the field value is the selected value
                        if (!mSelectedUserName.equals(fieldValue)) {
                            // user name is incorrect.
                            hasError = true;
                            editText.setError(getString(R.string.userNameErrorMsg));
                        }
                    } else if (!TextUtils.isEmpty(mUserName)) {
                        // if the user doesn't change the user name and it
                        // remains the same

                        // check if the username is exactly as the actual
                        // username
                        if (!mUserName.equals(fieldValue)) {
                            hasError = true;
                            editText.setError(getString(R.string.userNameErrorMsg));
                        }
                    } else {
                        // if the editText has been disabled, do not show the
                        // error
                        if (editText.isEnabled()) {
                            /*
                             * if the user just enters some value without selecting((ViewGroup)
                             * mDetailsTable.getChildAt(rowsCount)).getChildAt(1) from the
                             * auto-suggest
                             */
                            hasError = true;
                            editText.setError(getString(R.string.userNameErrorMsg));
                        }
                    }
                }
            }
            /*if (rowsCount == 0 && fieldValue.isEmpty()) {
                hasError = true;
            }*/
            // add the fieldName : fieldValue in the ContentValues
            modifiedValues.put(fieldName, editText.getText().toString());
            rowsCount++;
        }

        if (!hasError) {

            if (MODE == Util.EDIT_ORPHAN_MODE) {
                ServiceHelper.startServiceForUpdate(getActivity().getBaseContext(), uri, mModuleName, mSugarBeanId, modifiedValues);
            } else if (MODE == Util.EDIT_RELATIONSHIP_MODE) {
                ServiceHelper.startServiceForUpdate(getActivity().getBaseContext(), uri, mModuleName, mSugarBeanId, modifiedValues);
            } else if (MODE == Util.NEW_RELATIONSHIP_MODE) {
                modifiedValues.put(ModuleFields.DELETED, Util.NEW_ITEM);
                ServiceHelper.startServiceForInsert(getActivity().getBaseContext(), uri, mModuleName, modifiedValues);
            } else if (MODE == Util.NEW_ORPHAN_MODE) {
                modifiedValues.put(ModuleFields.DELETED, Util.NEW_ITEM);
                ServiceHelper.startServiceForInsert(getActivity().getBaseContext(), ContentUtils.getModuleUri(mModuleName), mModuleName, modifiedValues);
            }

            // finish();
        } else {
            ViewUtil.makeToast(getActivity().getBaseContext(), R.string.validationErrorMsg);
            hasError = false;
            mProgressDialog.cancel();
        }
        ViewUtil.dismissVirtualKeyboard(getActivity().getBaseContext(), v);
    }

    /**
     * importContact for invoking a subactivity for picking up contact from device contacts
     */
    public void importContact() {
        Intent intent = new Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI);
        startActivityForResult(intent, Util.IMPORT_CONTACTS_REQUEST_CODE);
    }

    @Override
    public void onActivityResult(int reqCode, int resultCode, Intent data) {
        super.onActivityResult(reqCode, resultCode, data);

        switch (reqCode) {
        case (Util.IMPORT_CONTACTS_REQUEST_CODE):
            if (resultCode == Activity.RESULT_OK) {
                getContactInfo(data);
            }
            break;
        }
    }

    protected void getContactInfo(Intent intent) {

        Cursor cursor = getActivity().managedQuery(intent.getData(), null, null, null, null);
        while (cursor.moveToNext()) {
            String contactId = cursor.getString(cursor.getColumnIndex(BaseColumns._ID));

            Cursor nameCursor = getActivity().getApplicationContext().getContentResolver().query(ContactsContract.Data.CONTENT_URI, null, ContactsContract.Data.MIMETYPE
                                            + " = ? AND "
                                            + ContactsContract.RawContactsEntity.CONTACT_ID
                                            + " = ? ", new String[] {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, contactId }, null);

            while (nameCursor.moveToNext()) {
                String givenName = nameCursor.getString(nameCursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME));
                String familyName = nameCursor.getString(nameCursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME));
                ((AutoCompleteTextView) mDetailsTable.findViewWithTag(ImportContactsUtility.getModuleFieldNameForContactsField(ModuleFields.FIRST_NAME))).setText(givenName);
                ((AutoCompleteTextView) mDetailsTable.findViewWithTag(ImportContactsUtility.getModuleFieldNameForContactsField(ModuleFields.LAST_NAME))).setText(familyName);
            }
            nameCursor.close();

            String hasPhone = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER));

            if (hasPhone.equalsIgnoreCase("1")) {
                hasPhone = "true";
            } else {
                hasPhone = "false";
            }

            if (Boolean.parseBoolean(hasPhone)) {
                Cursor phones = getActivity().getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, ContactsContract.CommonDataKinds.Phone.CONTACT_ID
                                                + " = " + contactId, null, null);
                while (phones.moveToNext()) {
                    String contactPhno = phones.getString(phones.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                    int type = phones.getInt(phones.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE));
                    if (ContactsContract.CommonDataKinds.Phone.TYPE_WORK == type) {
                        ((AutoCompleteTextView) mDetailsTable.findViewWithTag(ImportContactsUtility.getModuleFieldNameForContactsField(ModuleFields.PHONE_WORK))).setText(contactPhno);
                    }
                    if (ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE == type) {
                        ((AutoCompleteTextView) mDetailsTable.findViewWithTag(ImportContactsUtility.getModuleFieldNameForContactsField(ModuleFields.PHONE_MOBILE))).setText(contactPhno);
                    }
                }
                phones.close();
            }

            Cursor emails = getActivity().getContentResolver().query(ContactsContract.CommonDataKinds.Email.CONTENT_URI, null, ContactsContract.CommonDataKinds.Email.CONTACT_ID
                                            + " = " + contactId, null, null);
            while (emails.moveToNext()) {
                String contactEmail = emails.getString(emails.getColumnIndex(ContactsContract.CommonDataKinds.Email.DATA));
                ((AutoCompleteTextView) mDetailsTable.findViewWithTag(ImportContactsUtility.getModuleFieldNameForContactsField(ModuleFields.EMAIL1))).setText(contactEmail);
            }
            emails.close();
        }
        cursor.close();

    }// getContactInfo

    /** {@inheritDoc} */
    // @Override
    // TODO
    /*
     * public boolean onCreateOptionsMenu(Menu menu) { // Hold on to this // Inflate the currently
     * selected menu XML resource. MenuItem item; item = menu.add(1, R.id.save, 1, R.string.save);
     * item.setIcon(android.R.drawable.ic_menu_save); item.setAlphabeticShortcut('s'); return true;
     * }
     */
    /** {@inheritDoc} */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        /*
         * case R.id.save: saveModuleItem(getActivity().getCurrentFocus()); return true;
         */
        default:
            return true;
        }
        // return false;
    }

    /*
     * Status Handler, Handler updates the screen based on messages sent by the SugarService or any
     * tasks
     */
    private class StatusHandler extends Handler {
        StatusHandler() {
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
            case R.id.status:
                if (Log.isLoggable(TAG, Log.DEBUG))
                    Log.d(TAG, "Display Status");
                mProgressDialog.cancel();
                ViewUtil.makeToast(getActivity().getBaseContext(), (String) message.obj);
               /* if (ViewUtil.isTablet(getActivity()) && MODE != Util.NEW_ORPHAN_MODE) {
                    getActivity().getSupportFragmentManager().beginTransaction().remove(EditModuleDetailFragment.this).commit();
                    ModuleDetailFragment moduleDetailFragment = new ModuleDetailFragment();
                    getActivity().getSupportFragmentManager().beginTransaction().add(R.id.fragment_container_module_detail, moduleDetailFragment, "module_detail").commit();
                } else*/
                    getActivity().finish();
                break;
            }
        }
    }

    public static class AutoSuggestAdapter extends CursorAdapter implements Filterable {
        protected ContentResolver mContent;

        protected DatabaseHelper mDbHelper;

        public AutoSuggestAdapter(Context context, Cursor c) {
            super(context, c);
            mContent = context.getContentResolver();
            mDbHelper = new DatabaseHelper(context);
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            final LayoutInflater inflater = LayoutInflater.from(context);
            final LinearLayout view = (LinearLayout) inflater.inflate(R.layout.autosuggest_list_item, parent, false);
            ((TextView) view.getChildAt(0)).setText(cursor.getString(2));
            return view;
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            if (Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "bindView : " + cursor.getString(2));
            ((TextView) ((LinearLayout) view).getChildAt(0)).setText(cursor.getString(2));
        }

        @Override
        public String convertToString(Cursor cursor) {
            if (Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "convertToString : " + cursor.getString(2));
            return cursor.getString(2);
        }

    }

    public static class AccountsSuggestAdapter extends AutoSuggestAdapter {

        public AccountsSuggestAdapter(Context context, Cursor c) {
            super(context, c);
        }

        @Override
        public Cursor runQueryOnBackgroundThread(CharSequence constraint) {
            if (getFilterQueryProvider() != null) {
                return getFilterQueryProvider().runQuery(constraint);
            }

            StringBuilder buffer = null;
            String[] args = null;
            if (constraint != null) {
                buffer = new StringBuilder();
                buffer.append("UPPER(");
                buffer.append(AccountsColumns.NAME);
                buffer.append(") GLOB ?");
                args = new String[] { constraint.toString().toUpperCase() + "*" };
            }

            if (Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "constraint " + (constraint != null ? constraint.toString() : ""));

            return mContent.query(ContentUtils.getModuleUri(Util.ACCOUNTS), Accounts.LIST_PROJECTION, buffer == null ? null
                                            : buffer.toString(), args, Accounts.DEFAULT_SORT_ORDER);
        }
    }

    public static class UsersSuggestAdapter extends AutoSuggestAdapter {

        public UsersSuggestAdapter(Context context, Cursor c) {
            super(context, c);
        }

        @Override
        public Cursor runQueryOnBackgroundThread(CharSequence constraint) {
            if (getFilterQueryProvider() != null) {
                return getFilterQueryProvider().runQuery(constraint);
            }

            StringBuilder buffer = null;
            String[] args = null;
            if (constraint != null) {
                buffer = new StringBuilder();
                buffer.append("UPPER(");
                buffer.append(UserColumns.USER_NAME);
                buffer.append(") GLOB ?");
                args = new String[] { constraint.toString().toUpperCase() + "*" };
            }

            if (Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "constraint " + (constraint != null ? constraint.toString() : ""));

            return mContent.query(ContentUtils.getModuleUri(Util.USERS), Users.DETAILS_PROJECTION, buffer == null ? null
                                            : buffer.toString(), args, null);
        }
    }

    public class AccountsClickedItemListener implements AdapterView.OnItemClickListener {

        @Override
        public void onItemClick(AdapterView<?> adapterView, View view, int position, long l) {
            try {
                // Remembers the selected account name
                Cursor cursor = (Cursor) adapterView.getItemAtPosition(position);
                mSelectedAccountName = cursor.getString(2);
            } catch (Exception e) {
                Log.e(TAG, "cannot get the clicked index " + position);
            }

        }
    }

    public class UsersClickedItemListener implements AdapterView.OnItemClickListener {

        @Override
        public void onItemClick(AdapterView<?> adapterView, View view, int position, long l) {
            try {
                // Remembers the selected username
                Cursor cursor = (Cursor) adapterView.getItemAtPosition(position);
                mSelectedUserName = cursor.getString(2);
            } catch (Exception e) {
                Log.e(TAG, "cannot get the clicked index " + position);
            }

        }
    }

    private class SaveAction extends AbstractAction {

        public SaveAction() {
            super(R.drawable.save);
        }

        @Override
        public void performAction(View view) {
            saveModuleItem(getActivity().getCurrentFocus());
        }
    }  
    
    private class HomeAction extends AbstractAction {

        public HomeAction() {
            super(R.drawable.home);
        }

        @Override
        public void performAction(View view) {            
            DialogFragment newFragment = new HomeYesNoAlertDialogFragment().newInstance(R.string.discard);
            newFragment.show(getFragmentManager(), "HomeYesNoAlertDialog");
        }
    }
    
    public class HomeYesNoAlertDialogFragment extends DialogFragment {

        public HomeYesNoAlertDialogFragment newInstance(int title) {
            HomeYesNoAlertDialogFragment frag = new HomeYesNoAlertDialogFragment();
            Bundle args = new Bundle();
            args.putInt("title", title);
            frag.setArguments(args);
            return frag;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {

            return new AlertDialog.Builder(this.getActivity()).setTitle(R.string.discard).setMessage(R.string.discardAlert).setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    Intent intent = new Intent(EditModuleDetailFragment.this.getActivity(), DashboardActivity.class);
                    startActivity(intent);
                }
            }).setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                }
            }).create();
        }
    }

}
