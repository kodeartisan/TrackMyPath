package com.gts.trackmypath.data

import java.io.IOException
import java.lang.Exception

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.gts.trackmypath.common.Result
import com.gts.trackmypath.data.database.PhotoDao
import com.gts.trackmypath.data.database.PhotoEntity
import com.gts.trackmypath.data.database.toDomainModel
import com.gts.trackmypath.data.network.toDomainModel
import com.gts.trackmypath.data.network.toPhotoEntity
import com.gts.trackmypath.data.network.FlickrDataSource
import com.gts.trackmypath.data.network.PhotoResponseEntity
import com.gts.trackmypath.domain.model.Photo
import com.gts.trackmypath.domain.PhotoRepository

import timber.log.Timber

class PhotoRepositoryImpl(
    private val flickrDataSource: FlickrDataSource,
    private val photoDao: PhotoDao
) : PhotoRepository {

    override suspend fun searchPhotoByLocation(lat: String, lon: String): Result<Photo> {
        return try {
            // Radius used for geo queries, greater than zero and less than 20 miles (or 32 kilometers),
            // for use with point-based geo queries. The default value is 5 (km).
            // Set a radius of 100 meters. (default unit is km)
            return when (val response = flickrDataSource.searchPhoto(lat, lon, "0.1")) {
                is Result.Success -> {
                    val photosFromDb = photoDao.selectAllPhotos()
                    val photoFromFLickr = findUniquePhoto(response.data, photosFromDb)
                    photoFromFLickr?.let {
                        // save it in the DB
                        photoDao.insert(photoFromFLickr.toPhotoEntity())
                        Result.Success(photoFromFLickr.toDomainModel())
                    } ?: throw IOException("no photos retrieved from flickr")
                }
                is Result.Error -> {
                    Result.Error(response.exception)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "searchPhotoByLocation exception")
            Result.Error(IOException("searchPhotoByLocation exception", e))
        }
    }

    // Retrieve all photos from the DB
    override suspend fun loadAllPhotos(): Result<List<Photo>> {
        val photos = photoDao.selectAllPhotos()
        return if (photos.isNotEmpty()) {
            val result = photos.map { photoEntity -> photoEntity.toDomainModel() }
            Result.Success(result)
        } else {
            Result.Error(IOException("Failed to retrieve photos from database"))
        }
    }

    // Delete all photos in DB
    override suspend fun deletePhotos() {
        try {
            photoDao.deletePhotos()
        } catch (e: Exception) {
            Timber.e(e, "deletePhotos exception")
        }
    }

    // return the photo that doesn't already exist in the database
    private suspend fun findUniquePhoto(
        photosFromFlickr: List<PhotoResponseEntity>,
        photosFromDb: Array<PhotoEntity>
    ): PhotoResponseEntity? {
        return withContext(Dispatchers.Default) {
            if (photosFromDb.isNotEmpty()) {
                val iterator = photosFromFlickr.iterator()
                while (iterator.hasNext()) {
                    val photoResponseEntity = iterator.next()
                    Timber.d("fetched photo id ${photoResponseEntity.id}")
                    if (photosFromDb.all { photoEntity -> photoEntity.id != photoResponseEntity.id }) {
                        return@withContext photoResponseEntity
                    }
                }
            } else {
                return@withContext photosFromFlickr[0]
            }
            return@withContext null
        }
    }
}
