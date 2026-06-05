package com.example.myapp;

import android.content.Intent;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.core.content.ContextCompat;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

public class MainActivity extends AppCompatActivity {

    private static final String[] TAB_NAMES = {
        "Home", "Trending", "Music", "Gaming", "News", "Sports", "Comedy", "Education"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.app_name);
        }

        ViewPager2 viewPager = findViewById(R.id.view_pager);
        TabLayout tabLayout = findViewById(R.id.tab_layout);

        viewPager.setAdapter(new CategoryPagerAdapter(this));
        // Keep 1 page cached on each side for smooth swiping
        viewPager.setOffscreenPageLimit(1);

        new TabLayoutMediator(tabLayout, viewPager,
            (tab, position) -> tab.setText(TAB_NAMES[position])
        ).attach();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        // android:tint is not valid on menu items in AAPT2 — apply tint here instead
        MenuItem searchItem = menu.findItem(R.id.action_search);
        if (searchItem != null && searchItem.getIcon() != null) {
            searchItem.getIcon().setColorFilter(
                ContextCompat.getColor(this, R.color.white),
                PorterDuff.Mode.SRC_IN
            );
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_search) {
            startActivity(new Intent(this, SearchActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ── ViewPager2 adapter ─────────────────────────────────────────────────

    private static class CategoryPagerAdapter extends FragmentStateAdapter {
        CategoryPagerAdapter(FragmentActivity fa) {
            super(fa);
        }

        @Override
        public int getItemCount() {
            return TAB_NAMES.length;
        }

        @Override
        public Fragment createFragment(int position) {
            return HomeFragment.newInstance(position);
        }
    }
}
