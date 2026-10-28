package com.lagradost.cloudstream3.ui.result

import android.app.SearchManager
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.OvershootInterpolator
import android.view.animation.ScaleAnimation
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.CastItemBinding
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.NoStateAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.newSharedPool
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage

class ActorAdaptor(
    private var nextFocusUpId: Int? = null,
    private val focusCallback: (View?) -> Unit = {},
    private val onPersonSelected: (Actor) -> Unit = {},
) : NoStateAdapter<ActorData>(diffCallback = BaseDiffCallback(itemSame = { a, b ->
    a.actor.name == b.actor.name
})) {
    companion object {
        val sharedPool =
            newSharedPool { setMaxRecycledViews(CONTENT, 10) }
    }

    // Easier to store it here than to store it in the ActorData
    val inverted: HashMap<ActorData, Boolean> = hashMapOf()

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Any> {
        return ViewHolderState(
            CastItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun onClearView(holder: ViewHolderState<Any>) {
        when (val binding = holder.view) {
            is CastItemBinding -> {
                clearImage(binding.actorImage)
            }
        }
    }

    override fun onUpdateContent(holder: ViewHolderState<Any>, item: ActorData, position: Int) {
        when (val binding = holder.view) {
            is CastItemBinding -> {
                val anim: Animation = ScaleAnimation(
                    0.8f, 1f,
                    0.8f, 1f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f
                )
                anim.fillAfter = true
                anim.duration = 200
                anim.interpolator = OvershootInterpolator()
                binding.voiceActorImageHolder2.startAnimation(anim)
            }
        }
        super.onUpdateContent(holder, item, position)
    }

    private fun toggleInversion(holder: ViewHolderState<Any>) {
        val currentPosition = holder.bindingAdapterPosition
        if (currentPosition == RecyclerView.NO_POSITION) return

        val currentItem = getItemOrNull(currentPosition) ?: return
        if (currentItem.voiceActor?.image.isNullOrBlank()) return

        val isInverted = inverted.getOrDefault(currentItem, false)
        inverted[currentItem] = !isInverted
        onUpdateContent(holder, currentItem, currentPosition)
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: ActorData, position: Int) {
        when (val binding = holder.view) {
            is CastItemBinding -> {
                val itemView = binding.root
                val voiceActor = item.voiceActor
                val hasVoiceActorImage = !voiceActor?.image.isNullOrBlank()
                if (!hasVoiceActorImage) {
                    inverted.remove(item)
                }
                val isInverted = hasVoiceActorImage && inverted.getOrDefault(item, false)

                val (mainImg, vaImage) = if (!isInverted) {
                    Pair(item.actor.image, voiceActor?.image)
                } else {
                    Pair(voiceActor?.image, item.actor.image)
                }

                // Fix tv focus escaping the recyclerview
                if (position == 0) {
                    itemView.nextFocusLeftId = R.id.result_cast_items
                } else if ((position - 1) == itemCount) {
                    itemView.nextFocusRightId = R.id.result_cast_items
                }
                nextFocusUpId?.let {
                    itemView.nextFocusUpId = it
                }

                itemView.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        focusCallback(v)
                    }
                }

                val frontPerson = if (isInverted && voiceActor != null) voiceActor else item.actor
                val rearPerson = if (isInverted) item.actor else voiceActor
                val openFrontPerson = { onPersonSelected(frontPerson) }
                val openRearPerson = { rearPerson?.let(onPersonSelected) }
                binding.actorImage.apply {
                    contentDescription = "Open ${frontPerson.name} filmography"
                    setOnClickListener { openFrontPerson() }
                }
                binding.actorName.apply {
                    contentDescription = "Open ${frontPerson.name} filmography"
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { openFrontPerson() }
                    setOnFocusChangeListener { view, hasFocus -> if (hasFocus) focusCallback(view) }
                }
                binding.voiceActorImageHolder.apply {
                    isFocusable = hasVoiceActorImage
                    isClickable = hasVoiceActorImage
                    contentDescription = if (hasVoiceActorImage) {
                        rearPerson?.let { "Show ${it.name}" }
                    } else null
                    setOnFocusChangeListener { view, hasFocus ->
                        if (hasFocus) focusCallback(view)
                    }
                    setOnClickListener(if (hasVoiceActorImage) {
                        View.OnClickListener {
                            this@ActorAdaptor.toggleInversion(holder)
                        }
                    } else null)
                }
                binding.voiceActorName.apply {
                    if (rearPerson != null) {
                        contentDescription = "Open ${rearPerson.name} filmography"
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { openRearPerson() }
                        setOnFocusChangeListener { view, hasFocus -> if (hasFocus) focusCallback(view) }
                    } else {
                        setOnClickListener(null)
                        setOnFocusChangeListener(null)
                        isClickable = false
                        isFocusable = false
                    }
                }

                itemView.setOnClickListener {
                    if (isLayout(PHONE)) {
                        if (hasVoiceActorImage) {
                            toggleInversion(holder)
                        } else {
                            openFrontPerson()
                        }
                    } else {
                        openFrontPerson()
                    }
                }

                itemView.setOnLongClickListener {
                    if (isLayout(PHONE)) {
                        Intent(Intent.ACTION_WEB_SEARCH).apply {
                            putExtra(SearchManager.QUERY, item.actor.name)
                        }.also { intent ->
                            itemView.context.packageManager?.let { pm ->
                                if (intent.resolveActivity(pm) != null) {
                                    itemView.context.startActivity(intent)
                                }
                            }
                        }
                    }
                    true
                }

                binding.apply {
                    actorImage.loadImage(mainImg)

                    actorName.text = frontPerson.name
                    item.role?.let {
                        actorExtra.context?.getString(
                            when (it) {
                                ActorRole.Main -> {
                                    R.string.actor_main
                                }

                                ActorRole.Supporting -> {
                                    R.string.actor_supporting
                                }

                                ActorRole.Background -> {
                                    R.string.actor_background
                                }
                            }
                        )?.let { text ->
                            actorExtra.isVisible = true
                            actorExtra.text = text
                        }
                    } ?: item.roleString?.let {
                        actorExtra.isVisible = true
                        actorExtra.text = it
                    } ?: run {
                        actorExtra.isVisible = false
                    }

                    if (item.voiceActor == null) {
                        voiceActorImageHolder.isVisible = false
                        voiceActorName.isVisible = false
                    } else {
                        voiceActorName.text = rearPerson?.name
                        voiceActorName.isVisible = true
                        voiceActorImageHolder.isVisible = hasVoiceActorImage
                        voiceActorImage.loadImage(vaImage)
                    }
                }
            }
        }
    }
}
