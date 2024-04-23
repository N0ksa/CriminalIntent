package com.example.criminalintent

import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.criminalintent.databinding.FragmentCrimeDetailBinding
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date


private const val DATE_FORMAT = "EEE, MMM, dd"

class CrimeDetailsFragment : Fragment() {


    private val arg: CrimeDetailsFragmentArgs by navArgs()

    private val crimeDetailModelView: CrimeDetailsViewModel by viewModels {
        CrimeDetailsViewModelFactory(arg.crimeId)
    }


    private val selectSuspect = registerForActivityResult(ActivityResultContracts.PickContact()) {uri ->
            uri?.let {
                parseContactSelection(uri)
            }
    }

    private var photoName : String? = null

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) {didTakePhoto ->
        if (didTakePhoto && photoName != null) {
            crimeDetailModelView.updateCrime {oldCrime ->
                oldCrime.copy(photoFileName = photoName)
            }
        }

    }



    private val binding : FragmentCrimeDetailBinding
        get() = checkNotNull(_binding){
            "Cannot access binding because it is null. Is the view visible?"
        }

    private var _binding: FragmentCrimeDetailBinding? = null




    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentCrimeDetailBinding.inflate(inflater, container, false)

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.apply {
            crimeTitle.doOnTextChanged{text, _, _, _ ->
                crimeDetailModelView.updateCrime { oldCrime ->
                    oldCrime.copy(title = crimeTitle.text.toString())
                }

            }


           crimeSolved.setOnCheckedChangeListener { _, isChecked ->
                crimeDetailModelView.updateCrime { oldCrime ->
                    oldCrime.copy(isSolved = crimeSolved.isChecked)
                }
           }


           crimeSuspect.setOnClickListener{
               selectSuspect.launch(null)
           }

            val selectSuspectIntent = selectSuspect.contract.createIntent(
                requireContext(),
                null
            )

            crimeSuspect.isEnabled = canResolveIntent(selectSuspectIntent)


            crimeCamera.setOnClickListener{
                photoName = "IMG_${Date()}.JPG"
                val photoFile = File(requireContext().applicationContext.filesDir, photoName)

                val photoUri = FileProvider.getUriForFile(
                    requireContext(),
                    "com.example.criminalintent.fileprovider",
                    photoFile

                )
                takePhoto.launch(photoUri)
            }

        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){
                crimeDetailModelView.crime.collect {crime ->
                    crime?.let { updateUi(it) }
                }
            }

        }

        setFragmentResultListener(DatePickerFragment.REQUEST_KEY_DATE){
                _, bundle ->
            val newDate = bundle.getSerializable(DatePickerFragment.BUNDLE_KEY_DATE) as Date

            crimeDetailModelView.updateCrime { it.copy(date = newDate) }

        }

    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    private fun updateUi(crime: Crime){
        binding.apply {
            if (crimeTitle.text.toString() != crime.title) {
                crimeTitle.setText(crime.title)
            }

            crimeDate.text = crime.date.toString()
            crimeDate.setOnClickListener {
                findNavController().navigate(
                    CrimeDetailsFragmentDirections.selectDate(crime.date)
                )
            }

            crimeSolved.isChecked = crime.isSolved

            crimeReport.setOnClickListener{
                val reportIntent = Intent(Intent.ACTION_SEND).apply {

                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, getCrimeReport(crime))
                    putExtra(Intent.EXTRA_SUBJECT, R.string.crime_report_subject)
                }

                val chooserIntent = Intent.createChooser(reportIntent, getString(R.string.send_report))

                startActivity(chooserIntent)
            }

            crimeSuspect.text = crime.suspect.ifEmpty {
                getString(R.string.crime_suspect_text)
            }

            updatePhoto(crime.photoFileName)

        }

    }

    private fun getCrimeReport(crime: Crime) : String {
        val solvedString = if (crime.isSolved) {
            getString(R.string.crime_report_solved)
        } else {
            getString(R.string.crime_report_unsolved)
        }

        val dateString = DateFormat.format(DATE_FORMAT, crime.date).toString()

        val suspectText = if (crime.suspect.isBlank()) {
            getString(R.string.crime_report_no_suspect)

        } else {
            getString(R.string.crime_report_suspect, crime.suspect)
        }

        return getString(
            R.string.crime_report,
            crime.title, dateString, solvedString, suspectText)
    }

    private fun parseContactSelection(contactUri : Uri) {
        val queryFields = arrayOf(ContactsContract.Contacts.DISPLAY_NAME)

        val queryCursor = requireActivity().contentResolver
            .query(contactUri, queryFields, null, null, null)

        queryCursor?.use {cursor ->
            if (cursor.moveToFirst()) {
                val suspect = cursor.getString(0)
                crimeDetailModelView.updateCrime {oldCrime ->
                    oldCrime.copy(suspect = suspect)
                }
            }
        }

    }


    private fun canResolveIntent(intent: Intent) : Boolean {
        val packageManager : PackageManager = requireActivity().packageManager

        val resolvedActivity: ResolveInfo? =
            packageManager.resolveActivity(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY)

        return resolvedActivity != null
    }

    private fun updatePhoto(photoFileName : String?) {
        if (binding.crimePhoto.tag != photoFileName) {

            val photoFile = photoFileName?.let {
                File(requireContext().applicationContext.filesDir, it)
            }


            if (photoFile?.exists() == true) {

                binding.crimePhoto.doOnLayout { measuredView ->
                    val scaledBitmap = getScaledBitmap(
                        photoFile.path,
                        measuredView.width,
                        measuredView.height
                    )

                    binding.crimePhoto.setImageBitmap(scaledBitmap)
                    binding.crimePhoto.tag = photoFileName

                    binding.crimePhoto.contentDescription = getString(R.string.crime_photo_image_description)
                }



            } else {
                binding.crimePhoto.setImageBitmap(null)
                binding.crimePhoto.tag = null
                binding.crimePhoto.contentDescription = getString(R.string.crime_photo_no_image_description)
            }

        }


    }

}