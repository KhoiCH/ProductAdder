package com.khoich.productadder

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import com.khoich.productadder.databinding.ActivityMainBinding
import com.skydoves.colorpickerview.ColorEnvelope
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID


//Change the security access settings in the firebase cloud and firestore
class MainActivity : AppCompatActivity() {
    companion object {
        private const val IMAGE = "image/*"
    }

    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    val selectedColors = mutableListOf<Int>()
    private val selectedImages = mutableListOf<Uri>()
    private val fireStore = Firebase.firestore

    // storage dung de add anh
    private val storage = Firebase.storage.reference

    private val selectImagesActivityResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val intent = result.data

                //Multiple images selected, nếu người dùng chỉ chọn 1 ảnh thì clip data sẽ null
                if (intent?.clipData != null) {
                    val count = intent.clipData?.itemCount ?: 0
                    (0 until count).forEach {
                        val imagesUri = intent.clipData?.getItemAt(it)?.uri
                        imagesUri?.let { uri ->
                            selectedImages.add(uri)
                        }
                    }

                    //One images was selected
                } else {
                    val imageUri = intent?.data
                    imageUri?.let {
                        selectedImages.add(it)
                    }
                }
                updateImages()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        //4
        binding.buttonColorPicker.setOnClickListener {
            pickColor()
        }

        //6
        binding.buttonImagesPicker.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            intent.type = IMAGE
            selectImagesActivityResult.launch(intent)
        }
    }

    private fun pickColor() {
        ColorPickerDialog.Builder(this)
            .setTitle("Product color")
            .setPositiveButton("Select", object : ColorEnvelopeListener {
                override fun onColorSelected(envelope: ColorEnvelope?, fromUser: Boolean) {
                    envelope?.let {
                        selectedColors.add(it.color)
                        updateColors()
                    }
                }
            }).setNegativeButton("Cancel") { colorPicker, _ ->
                colorPicker.dismiss()
            }.show()
    }


    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        //1
        if (item.itemId == R.id.saveProduct) {
            val productValidation = validateInformation()
            if (!productValidation) {
                Toast.makeText(this, "Check your inputs", Toast.LENGTH_SHORT).show()
                return false
            }
            saveProducts() {
                Log.d("test", it.toString())
            }
        }
        return super.onOptionsItemSelected(item)
    }

    //2
    private fun validateInformation(): Boolean {
        return selectedImages.isNotEmpty()
                && binding.edName.text.toString().trim().isNotEmpty()
                && binding.edCategory.text.toString().trim().isNotEmpty()
                && binding.edPrice.text.toString().trim().isNotEmpty()
    }

    //3
    private fun saveProducts(state: (Boolean) -> Unit) {
        val name = binding.edName.text.toString().trim()
        val category = binding.edCategory.text.toString().trim()
        val price = binding.edPrice.text.toString().trim()
        val offerPercentage = binding.edOfferPercentage.text.toString().trim()
        val productDescription = binding.edDescription.text.toString().trim()
        val sizes = getSizesList(binding.edSizes.text.toString().trim())
        val images = mutableListOf<String>()
        //bien duoi dung de duyet anh dua vao images
        val imagesByteArrays = getImagesByteArrays() //7

        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                showLoading()
            }
            try {
                async {
                    Log.d("test1", "test")
                    imagesByteArrays.forEach {
                        val id = UUID.randomUUID().toString()
                        launch {
                            val imagesStorage = storage.child("products/images/$id")
                            val result = imagesStorage.putBytes(it).await()
                            val downloadUrl = result.storage.downloadUrl.await().toString()
                            images.add(downloadUrl)
                        }
                    }
                }.await()
            } catch (e: java.lang.Exception) {
                withContext(Dispatchers.Main) {
                    hideLoading()
                }
                state(false)
            }

//            Log.d("test2", "test")

            val product = Product(
                UUID.randomUUID().toString(),
                name,
                category,
                price.toFloat(),
                if (offerPercentage.isEmpty()) null else offerPercentage.toFloat(),
                productDescription.ifEmpty { null },
                selectedColors,
                sizes,
                images
            )

            fireStore.collection("Products").add(product)
                .addOnSuccessListener {
                    state(true)
                    hideLoading()
                }.addOnFailureListener {
                    Log.e("test2", it.message.toString())
                    state(false)
                    hideLoading()
                }
        }
    }

    private fun hideLoading() {
        binding.progressbar.visibility = View.INVISIBLE
    }

    private fun showLoading() {
        binding.progressbar.visibility = View.VISIBLE

    }

    private fun getImagesByteArrays(): List<ByteArray> {
        val imagesByteArray = mutableListOf<ByteArray>()
        selectedImages.forEach {
            val stream = ByteArrayOutputStream()
            val imageBmp = MediaStore.Images.Media.getBitmap(contentResolver, it)
            if (imageBmp.compress(Bitmap.CompressFormat.JPEG, 85, stream)) {
                val imageAsByteArray = stream.toByteArray()
                imagesByteArray.add(imageAsByteArray)
            }
        }
        return imagesByteArray
    }

    private fun getSizesList(sizes: String): List<String>? {
        if (sizes.isEmpty()) return null
        return sizes.split(",").map {
            it.trim()
        }
    }

    //5
    private fun updateColors() {
        var colors = ""
        selectedColors.forEach {
            colors = "$colors ${Integer.toHexString(it)}, "
        }
        binding.tvSelectedColors.text = colors
    }

    private fun updateImages() {
        binding.tvSelectedImages.text = selectedImages.size.toString()
    }


}